# HirInterpreter 性能分析报告

## 基准测试数据

```
[10/19] 递归调用 — fib(20) 递归 x 100
  Nova 解释:     379.18 ms
  Nova 编译:     1.3100 ms
  Java 原生:     1.4624 ms
  解释/原生:      259.3x
  编译/原生:        0.9x
```

测试代码：
```nova
fun fib(n: Int): Int {
    if (n <= 1) return n
    return fib(n - 1) + fib(n - 2)
}
```

fib(20) 产生 **21,891 次递归调用**，每次调用涉及：
- 1 次比较 (`n <= 1`)
- 1 次加法 (`fib(n-1) + fib(n-2)`)（叶节点除外）
- 2 次减法 (`n - 1`, `n - 2`)（叶节点除外）
- 2 次递归调用（叶节点除外）

---

## 瓶颈分析（按影响排序）

### 1. 算术运算 instanceof 级联 — 预估占比 30-35%

**位置**: `Interpreter.java` 的 `doAdd()` / `doSub()` / `doMul()` 等方法

**问题**: 每次算术运算经过 6+ 层 instanceof 检查：

```java
// doAdd() 路径 — Int 是最后才检查的！
if (a instanceof Double || b instanceof Double) → Double 路径
if (a instanceof Float || b instanceof Float)   → Float 路径
if (a instanceof Long || b instanceof Long)     → Long 路径
if (a.isNumber() && b.isNumber())               → Int 路径（终于到了）
```

在到达 Int 快速路径之前还有额外检查：
- `isString()` × 2（字符串拼接检查）
- `isList()` × 2（列表合并检查）
- 共 **8 次虚方法调用 + 6 次 instanceof** 才到达 Int 加法

**fib(20) 数据**: ~13,529 次加法 + ~13,529 次减法 = **~27,058 次算术运算**
- 总 instanceof 检查: **~162K 次**
- 总虚方法调用 (isString/isList/isNumber): **~216K 次**

### 2. 比较运算 double 转换 — 预估占比 15-20%

**位置**: `Interpreter.java` 的 `compare()` 方法

**问题**: 所有数值比较先转为 double：

```java
private int compare(NovaValue a, NovaValue b, AstNode node) {
    // ... type checks ...
    double da = asDouble(a);  // Int → double 装箱转换
    double db = asDouble(b);
    return Double.compare(da, db);
}
```

`n <= 1` 本可以是简单的 int 比较，却变成了：
1. `asDouble(a)` — instanceof 检查 + `intValue()` + int→double 转换
2. `asDouble(b)` — 同上
3. `Double.compare()` — double 比较（比 int 比较慢 3-5x）

**fib(20) 数据**: **~21,891 次比较** → ~43,782 次 int→double 转换

### 3. Environment 分配与查找 — 预估占比 20-25%

**位置**: `HirInterpreter.java` 的 `executeHirFunctionBody()` + `Environment.java`

**问题**:
- 每次函数调用创建新 `Environment`（112 字节，含两个数组）
- 参数绑定通过 `defineValFast()` — 无重复检查，但仍有数组操作
- 变量查找 `tryGet()` 是线性搜索 + 父环境链遍历

```java
// Environment 内存布局: 每次调用约 112 bytes
Object header:    16 bytes
String[] keys:    16 + 4*4 = 32 bytes  (初始容量 4)
NovaValue[] vals: 16 + 4*8 = 48 bytes
parent ref:       8 bytes
size:             4 bytes
```

**fib(20) 数据**: 21,891 次调用 × 112 bytes = **~2.4 MB 分配**
- GC 压力显著（短命对象大量创建）

### 4. ControlFlow 异常用于 return — 预估占比 10-15%

**位置**: `HirInterpreter.java` 的 `executeHirFunctionBody()` line ~530

**问题**: `return` 语句通过抛出 `ControlFlow` 异常实现：

```java
try {
    accept(body, env);
} catch (ControlFlow cf) {
    if (cf.getType() == ControlFlow.Type.RETURN) {
        return cf.getValue();
    }
}
```

虽然 `ControlFlow` 禁用了 `fillInStackTrace()`，但仍有：
- 异常对象分配（~48 bytes）
- try-catch 机制开销
- JVM 无法对 throw/catch 路径进行内联优化

**fib(20) 数据**: **21,891 次异常抛出/捕获**

### 5. HirInterpreter visitBinary 二次包装 — 预估占比 5-10%

**位置**: `HirInterpreter.java` 的 `visitBinary()` line ~1622

**问题**: visitBinary 不直接调用 doAdd，而是通过 try-catch 包装：

```java
case ADD: {
    try {
        return doAdd(left, right, node.toAstNode());
    } catch (NovaRuntimeException e) {
        throw hirError(e.getMessage(), node);
    }
}
```

每次算术运算额外开销：
- `node.toAstNode()` — 可能创建新对象
- try-catch 包装 — 抑制 JIT 优化
- error re-wrapping — 虽然正常路径不触发，但 try-catch 结构本身有开销

### 6. NovaInt 装箱 — 预估占比 5%

**位置**: `NovaInt.java`

**现状**: NovaInt 有 -128~1024 的缓存，fib(20) 的值范围 0~6765，大部分命中缓存。

但每次 `doAdd` / `doSub` 结果仍需：
1. 调用 `NovaInt.of(result)`
2. 范围检查 → 缓存查找或 new 分配

---

## 优化方案（按 ROI 排序）

### 方案 A: Int 快速路径 — 预期提升 40-50%

**思路**: 在 doAdd/doSub/compare 等方法最前面加 Int×Int 快速路径，跳过所有 instanceof 级联。

```java
// doAdd 优化
if (a instanceof NovaInt && b instanceof NovaInt) {
    return NovaInt.of(((NovaInt)a).getValue() + ((NovaInt)b).getValue());
}
// 然后才是原有的 Double/Float/Long 级联...
```

```java
// compare 优化
if (a instanceof NovaInt && b instanceof NovaInt) {
    return Integer.compare(((NovaInt)a).getValue(), ((NovaInt)b).getValue());
}
// 然后才是原有的 double 转换路径...
```

**优点**: 改动极小（每个方法加 3 行），Int 是最常用类型，一次 instanceof 替代 6 次
**风险**: 极低

### 方案 B: HirInterpreter 算术运算直接内联 — 预期提升 10-15%

**思路**: 在 HirInterpreter.visitBinary 中直接处理 Int+Int，不委托给父类 doAdd。

```java
case ADD: {
    if (left instanceof NovaInt && right instanceof NovaInt) {
        return NovaInt.of(((NovaInt)left).getValue() + ((NovaInt)right).getValue());
    }
    try {
        return doAdd(left, right, node.toAstNode());
    } catch (NovaRuntimeException e) {
        throw hirError(e.getMessage(), node);
    }
}
```

**优点**: 消除 try-catch 包装 + toAstNode() 开销（Int 路径）
**风险**: 低，非 Int 类型仍走原路径

### 方案 C: Environment 对象池 — 预期提升 10-15%

**思路**: 使用 ThreadLocal 对象池复用 Environment，避免大量短命对象的 GC 压力。

```java
// Environment 对象池
private static final ThreadLocal<ArrayDeque<Environment>> POOL =
    ThreadLocal.withInitial(() -> new ArrayDeque<>(64));

public static Environment acquire(Environment parent) {
    ArrayDeque<Environment> pool = POOL.get();
    Environment env = pool.poll();
    if (env == null) env = new Environment(parent);
    else env.reset(parent);
    return env;
}

public void release() {
    clear();
    POOL.get().offer(this);
}
```

**优点**: 消除 ~2.4MB/fib(20) 的分配压力
**风险**: 中等，需要确保 release 时机正确

### 方案 D: return 值直接传递替代异常 — 预期提升 5-10%

**思路**: 使用返回值携带 return 信号，不抛异常。

可选实现：
1. 使用 `NovaValue` 的特殊子类 `ReturnSignal` 作为标记
2. 在 `executeHirFunctionBody` 中直接检查返回值

```java
// 方案 D1: ReturnSignal 标记
static final class ReturnSignal extends NovaValue {
    final NovaValue value;
    // ...
}

// visitReturn 中
return new ReturnSignal(value);  // 替代 throw new ControlFlow(RETURN, value)

// executeHirFunctionBody 中
NovaValue result = accept(body, env);
if (result instanceof ReturnSignal) {
    return ((ReturnSignal) result).value;
}
```

**优点**: 消除 21K 次异常抛出/捕获
**风险**: 中等，需要在所有 accept 调用处检查 ReturnSignal

### 方案 E: 变量槽位直接访问 — 预期提升 5-8%

**思路**: HIR 阶段已有变量解析信息，利用 `getAtSlot(depth, slot)` 替代 `tryGet(name)` 线性搜索。

**优点**: O(1) 变量访问替代 O(n) 线性搜索
**风险**: 需要 HIR 变量解析器支持

---

## 优化优先级建议

| 优先级 | 方案 | 预期提升 | 实现难度 | 风险 |
|--------|------|----------|----------|------|
| P0 | A: Int 快速路径 | 40-50% | 极低 | 极低 |
| P0 | B: visitBinary 内联 | 10-15% | 低 | 低 |
| P1 | C: Environment 池 | 10-15% | 中 | 中 |
| P1 | D: return 直接传递 | 5-10% | 中 | 中 |
| P2 | E: 变量槽位访问 | 5-8% | 高 | 中 |

**推荐实施顺序**: A → B → C → D（方案 A+B 组合预期可将 259x 降至 ~130-150x）

---

## 附录：fib(20) 热路径调用统计

```
fib(20) 总调用次数: 21,891
  ├── compare (n <= 1):      21,891 次
  ├── doSub (n-1, n-2):      ~13,529 × 2 = ~27,058 次
  ├── doAdd (fib + fib):     ~13,529 次
  ├── Environment 创建:       21,891 次
  ├── ControlFlow 抛出:       21,891 次
  └── 函数查找 (fib):         ~27,058 次

总 instanceof 检查: ~197,000 次
总虚方法调用:       ~306,000 次
总内存分配:          ~2.4 MB (Environment) + ~1.0 MB (ControlFlow) = ~3.4 MB
```
