# NovaLang 架构分析报告

> 分析日期：2026-02-22
> 分析范围：全部 8 个模块，386 个源文件，约 25,000+ 行代码

---

## 一、项目全局概览

### 1.1 模块依赖拓扑

```
nova-runtime-api          ← 基础层：值类型、stdlib 核心
    ↑
nova-compiler             ← 前端：Lexer → Parser → AST → 语义分析
    ↑
nova-ir                   ← 中端：HIR + MIR + 优化管线 + 字节码生成
    ↑
nova-runtime              ← 解释器：AST/HIR/MIR 三套执行引擎
    ↑
┌───┼───────┐
nova-cli  nova-script  nova-lsp   ← 应用层
```

### 1.2 编译/执行管线

```
源码 → Lexer → Parser → AST
                         ├─→ [SemanticAnalyzer]（语义检查，可选）
                         ├─→ AstToHirLowering → HIR
                         │                       ├─→ [HIR 优化] → HirEvaluator（解释执行）
                         │                       └─→ HirToMirLowering → MIR
                         │                                              ├─→ [MIR 优化] → MirInterpreter（解释执行）
                         │                                              └─→ [MIR 优化] → MirCodeGenerator（字节码）
                         └─→ Interpreter（直接 AST 解释，已弃用但代码仍在）
```

### 1.3 源文件统计

| 模块 | 源文件数 | 职责 |
|------|---------|------|
| nova-runtime-api | ~60 | 值类型、stdlib 核心 |
| nova-compiler | ~70 | 词法/语法/语义分析、AST 定义 |
| nova-ir | ~35 | HIR/MIR 定义、lowering、优化、字节码生成 |
| nova-runtime | ~80 | 解释器（3 套执行引擎）、Java 互操作、stdlib 扩展 |
| nova-cli | 6 | CLI 入口、REPL |
| nova-lsp | 11 | Language Server Protocol |
| nova-script | 3 | JSR-223 ScriptEngine |
| nova-example | 5 | 嵌入式使用示例 |

---

## 二、核心架构问题

### 问题 1：三套执行引擎并存，大量代码重复（严重）

这是当前架构最严重的问题。`nova-runtime` 中存在三个独立的执行引擎：

| 引擎 | 文件 | 行数 | 状态 |
|------|------|------|------|
| AST 解释器 | `Interpreter.java` | ~1,403 | 已弃用但代码仍在维护 |
| HIR 求值器 | `HirEvaluator.java` | ~2,153 | 当前主路径 |
| MIR 解释器 | `MirInterpreter.java` | ~3,235 | 新优化路径 |

辅助文件：`FunctionExecutor.java`（~909 行）、`Environment.java`（~463 行）

**总计：约 8,163 行代码服务于"执行"这一个职责。**

#### 重复热点 1：二元运算（最严重）

同一套运算逻辑（加减乘除模、比较、位运算）被实现了 **3-4 次**：

- `Interpreter.doAdd/doSub/doMul/doDiv/doMod`：~250 行，对每种运算符分别处理 `NovaInt×NovaInt`、`Number×Number`、`String+any`、`List+List`、运算符重载
- `HirEvaluator.visitBinary`：~150 行，复制了 `Int×Int` 快速路径，但最终又 **委托回 `Interpreter.doAdd()`**，形成混乱的调用链
- `MirInterpreter.executeBinaryRaw` + `executeBinary` + `generalBinary`：~200 行，完全独立实现了 3 种变体（raw int 路径、boxed int 路径、通用路径），每种都有 16-20 个 case 的 switch

```
重复总量：~600+ 行近似相同的二元运算逻辑
```

#### 重复热点 2：函数调用/执行

`FunctionExecutor.executeBoundMethod()` 中 HirFunctionValue 和 HirLambdaValue 的执行体几乎相同（~215 行）：
- 都创建 `Environment`
- 都绑定参数（但用了两个几乎一样的方法：`bindParams` vs `bindLambdaParams`）
- 都执行函数体
- 都用同样的 try-catch 处理 return 信号

#### 重复热点 3：访问器逻辑

以下操作在 3 个引擎中各实现一次：

| 操作 | Interpreter | HirEvaluator | MirInterpreter |
|------|:-:|:-:|:-:|
| 变量解析 | ✓ | ✓ | ✓ |
| 字段访问 | ✓ | ✓ | ✓ |
| 函数调用 | ✓ | ✓ | ✓ |
| 类型检查 (is) | ✓ | ✓ | ✓ |
| 运算符重载 | ✓ | ✓ | ✓ |
| 集合索引 | ✓ | ✓ | ✓ |

#### 影响

- 修一个语义 bug 需要改 3 处
- 新增运算符/类型需要在 3 个地方同步更新
- 每次重构的改动面极大，风险不可控

---

### 问题 2：AST 和 HIR 节点体系完全并行（严重）

两套完全平行的节点层次结构：

```
AST 体系（92 种节点）           HIR 体系（38 种节点）
├─ AstNode                     ├─ HirNode
│  ├─ Declaration (15)         │  ├─ HirDecl (9)
│  ├─ Statement (12)           │  ├─ HirStmt (10)
│  ├─ Expression (35)          │  └─ HirExpr (20+)
│  └─ TypeRef (6)              └─ HirType (4)
├─ AstVisitor (46 方法)        ├─ HirVisitor (39 方法)
└─ 各自独立的 accept/visit     └─ 各自独立的 accept/visit
```

**问题本质：**
- 每种 AST 节点都有一个 "对应的" HIR 节点，区别仅在于：HIR 合并了一些相似节点（如 `IfStmt` + `IfExpr` → `HirIf` + `HirConditionalExpr`）、脱糖了一些语法糖（如 `ElvisExpr` → `HirIf(nullCheck)`）
- `AstToHirLowering` 需要为每种 AST 节点编写转换代码，本质是"换了个类名重建一遍树"
- 两个 Visitor 接口都需要实现者覆盖大量方法，新增语言特性需要改动 AST + HIR + Lowering 三处

---

### 问题 3：三套并行的类型系统（严重）

| 类型系统 | 位置 | 用途 | 关键类 |
|---------|------|------|--------|
| AST TypeRef | nova-compiler/ast/type/ | Parser 输出，保留原始语法形式 | `SimpleType`, `NullableType`, `GenericType`, `FunctionType` |
| NovaType | nova-compiler/analysis/types/ | 语义分析，类型推断和兼容性检查 | `PrimitiveNovaType`, `ClassNovaType`, `FunctionNovaType`, `TypeParameterType` |
| HirType | nova-ir/hir/type/ | IR 代码生成 | `PrimitiveType`, `ClassType`, `FunctionType`, `UnresolvedType` |

**影响：**
- 每套类型系统有独立的 equality/hash 实现
- `AstToHirLowering.lowerType()` 需要完整遍历 TypeRef → HirType 的映射
- `SemanticAnalyzer` 需要 TypeRef → NovaType 的另一套映射
- 三份类型数据的一致性难以保证

---

### 问题 4：模块依赖关系不合理（中等）

#### 4.1 间接循环依赖

```
nova-compiler
    ↓ api
nova-ir
    ↑ testImplementation（nova-compiler 测试依赖 nova-ir）
```

虽然 `testImplementation` 不构成编译时循环，但暗示模块边界不清晰——compiler 本不应该知道 IR 的存在。

#### 4.2 nova-runtime 过度依赖编译栈

```gradle
// nova-runtime/build.gradle
api project(':nova-ir')  // 传递依赖: nova-ir → nova-compiler → nova-runtime-api
```

解释器运行时被迫加载完整的编译器和 IR 模块（包含 AST 节点定义、Parser、Lexer 等），导致：
- 启动时类加载量增大
- 运行时 JAR 包含大量不需要的编译器代码
- 模块职责不清——"运行时"不应包含"编译器"

#### 4.3 nova-lsp 依赖整个 runtime

```gradle
// nova-lsp/build.gradle
implementation project(':nova-runtime')  // 仅为了 @NovaType 注解类的反射扫描
```

LSP 服务器只需要：Parser + AST + 语义分析 + 少量类型注解定义，却引入了整个 runtime + IR + compiler 全栈依赖。

#### 4.4 依赖图可视化

```
期望的依赖关系:                    实际的依赖关系:

nova-runtime-api                   nova-runtime-api
    ↑                                  ↑
nova-compiler                      nova-compiler ←──────┐
    ↑                                  ↑                │ (test)
nova-ir                            nova-ir ─────────────┘
    ↑                                  ↑
nova-runtime                       nova-runtime（加载了完整编译栈）
                                       ↑
                                   nova-lsp（加载了完整运行时 + 编译栈）
```

---

### 问题 5：标准库实现分裂（中等）

标准库函数散布在两个位置：

| 位置 | 文件数 | 内容 | 可被编译器使用 |
|------|--------|------|:-:|
| `nova-runtime-api/stdlib/` | ~26 | 核心 stdlib（Math、String 扩展、List 扩展等） | ✓ |
| `nova-runtime/interpreter/stdlib/` | 8 | IO、JSON、HTTP、Regex、Time、System、Concurrent、Test | ✗ |

**问题：**
- 约 1,100 行解释器专用 stdlib 代码（IO、JSON、HTTP 等）**无法被编译路径使用**
- 编译后的字节码调用 `StdlibRegistry` 中的函数，但 IO/JSON/HTTP 等只在解释器中注册
- 这意味着：在解释器中能用 `readFile("a.txt")`，编译后运行就报 `NoSuchMethodError`
- 语言行为在两种执行模式下不一致

---

### 问题 6：AstToHirLowering 单类过载（中等）

`AstToHirLowering.java` 承担了过多职责：

1. **92 种 AST 节点 → 38 种 HIR 节点的映射转换**
2. **13 条脱糖规则**（Elvis → if-null、SafeCall → let-if、ErrorPropagation → try-catch 等）
3. **类型注解转换**（TypeRef → HirType）
4. **作用域/导入处理**
5. **注解处理**

应该将"脱糖"和"节点转换"拆分为独立的阶段：
```
期望：AST → [Desugaring Pass] → SimplifiedAST → [结构转换] → HIR
实际：AST → [AstToHirLowering（混合脱糖+转换）] → HIR
```

---

### 问题 7：HirToMirLowering 代码爆炸（中等）

`HirToMirLowering.java` 管理大量嵌套上下文状态（60+ 个成员变量）：

```java
private final Deque<HirNode> finallyStack = new ArrayDeque<>();
private final Deque<Set<String>> lambdaCaptureStack = new ArrayDeque<>();
private final Map<String, Integer> boxedMutableCaptures = new HashMap<>();
// ... 60+ 个类似的上下文管理字段
```

单一类处理：控制流降级、Lambda/闭包处理、try-finally、Java 互操作、扩展函数、属性访问器。任何一个方面的改动都可能影响其他部分。

---

### 问题 8：控制流管理混乱（中等）

`Interpreter` + `FunctionExecutor` 中存在两种并行的 return 机制：

1. **ThreadLocal 标志**：`getHasReturn()` / `setHasReturn()` / `getReturnValue()`
2. **异常流**：`ControlFlow` 异常（`ControlFlow.Type.RETURN`）

两种机制同时存在，在函数执行时需要同时维护：
```java
boolean savedHR = interp.getHasReturn();
interp.setHasReturn(false);
try {
    // 执行 body
    if (interp.getHasReturn()) return interp.getReturnValue();  // 机制1
} catch (ControlFlow cf) {
    if (cf.getType() == ControlFlow.Type.RETURN) return cf.getValue();  // 机制2
} finally {
    interp.setHasReturn(savedHR);  // 恢复状态
}
```

---

### 问题 9：Java 互操作仅解释器可用（轻微但重要）

Java 互操作的核心实现全部在 `nova-runtime/interpreter/` 中：

| 文件 | 功能 |
|------|------|
| `JavaInterop.java` | Java 类加载、方法调用 |
| `JavaInteropHelper.java` | 委托创建、接口代理、构造器推导 |
| `JavaSubclassFactory.java` | 动态 Java 子类生成 |
| `MemberResolver.java` | Java 成员解析 |
| `MemberDispatcher.java` | Java 成员分派 |
| `SamProxyFactory.java` | SAM 接口代理 |

编译路径（`MirCodeGenerator`）生成的字节码需要通过 `nova-runtime-api` 中有限的互操作支持来访问 Java 类，功能远不如解释器路径完整。

---

### 问题 10：优化管线缺乏框架支持（轻微）

`PassPipeline` 当前是硬编码的 Pass 列表：

```java
public static PassPipeline createDefault() {
    pipeline.addHirPass(new HirInlineExpansion());
    pipeline.addHirPass(new HirConstantFolding());
    pipeline.addHirPass(new HirDeadCodeElimination());
    pipeline.addMirPass(new DeadBlockElimination());
    // ... 硬编码顺序
}
```

缺少：
- Pass 之间的依赖声明
- 分析结果缓存（Pass 可能重复计算 CFG/支配树等）
- Pass 失效通知机制
- Pass 组合/条件执行能力

---

## 三、问题影响矩阵

| # | 问题 | 严重度 | 影响范围 | 修复难度 | 重构优先级 |
|---|------|--------|---------|---------|-----------|
| 1 | 三套执行引擎代码重复 | 🔴 高 | 运行时全部 | 高 | **P0** |
| 2 | AST/HIR 节点体系并行 | 🔴 高 | 编译+运行全链路 | 高 | **P0** |
| 3 | 三套并行类型系统 | 🔴 高 | 编译+分析全链路 | 高 | **P1** |
| 4 | 模块依赖不合理 | 🟠 中 | 构建+部署 | 中 | **P1** |
| 5 | stdlib 实现分裂 | 🟠 中 | 语言一致性 | 低 | **P1** |
| 6 | AstToHirLowering 过载 | 🟠 中 | IR 可维护性 | 中 | **P2** |
| 7 | HirToMirLowering 爆炸 | 🟠 中 | IR 可维护性 | 高 | **P2** |
| 8 | 控制流管理混乱 | 🟠 中 | 解释器可维护性 | 中 | **P2** |
| 9 | Java 互操作分裂 | 🟡 低 | 编译模式功能 | 中 | **P3** |
| 10 | 优化管线无框架 | 🟡 低 | 编译优化 | 低 | **P3** |

---

## 四、重构建议

### 4.1 统一运算核心（解决问题 1）

**目标：** 将三套执行引擎共享的运算逻辑提取到单一位置。

```
nova-runtime-api（新增）
└── nova.runtime.ops
    ├── BinaryOps.java      ← 所有二元运算的唯一实现
    │   ├── add(NovaValue, NovaValue) → NovaValue
    │   ├── sub(NovaValue, NovaValue) → NovaValue
    │   ├── mul / div / mod / compare / ...
    │   └── tryOperatorOverload(...)
    ├── UnaryOps.java       ← 所有一元运算
    ├── TypeOps.java        ← 类型检查 / 转换
    └── CollectionOps.java  ← 索引、切片、展开
```

**效果：**
- HirEvaluator / MirInterpreter / Interpreter 都调用 `BinaryOps.add()`
- MirInterpreter 仍可保留 raw int 快速路径（先检查 raw，不命中再 fallback 到 `BinaryOps`）
- 新增运算符只需改一处
- ~600 行重复代码 → ~200 行共享实现

### 4.2 消除 AST/HIR 双重节点体系（解决问题 2）

**方案 A：保留 HIR 但自动生成映射**

为 AST 节点添加 `@LowerTo(HirXxx.class)` 注解，用注解处理器自动生成 `AstToHirLowering` 的大部分转换代码，开发者只需手写脱糖规则。

**方案 B：合并为统一 IR（更激进）**

用一个统一的节点体系替代 AST + HIR：
```
源码 → Parser → UnifiedIR（保留脱糖前的语法糖标记）
                    ↓ Desugaring Passes（独立的脱糖 pass）
                    ↓
               SimplifiedIR（脱糖后，等价于当前 HIR）
                    ↓ Lowering
                    ↓
                   MIR → 字节码
```

**方案 C：让 HIR 直接复用 AST 节点（最保守）**

不创建新的 HIR 节点类，而是在 AST 节点上附加降级信息（annotation / metadata），HIR 阶段的操作直接操作已标注的 AST 树。

**建议采用方案 A**——保持当前的两级结构但减少手写转换代码，改动量适中。

### 4.3 统一类型系统（解决问题 3）

**建议：** 以 NovaType 为编译器内部唯一类型表示。

```
Parser                    → TypeRef（短暂存在）
                              ↓ 立即转换
SemanticAnalyzer / IR     → NovaType（唯一的编译器内部类型）
                              ↓ 直接使用
HIR / MIR / CodeGen       → NovaType（不再有 HirType）
```

删除 `HirType` 体系，在 HIR 节点中直接使用 `NovaType`，消除 `lowerType()` 转换开销。

### 4.4 重构模块依赖（解决问题 4）

**目标架构：**

```
nova-common               ← 新模块：共享注解、值类型基础、ops
    ↑
nova-runtime-api           ← 运行时值类型、stdlib
    ↑
nova-compiler              ← 前端（Lexer/Parser/AST/语义分析）
    ↑
nova-ir                    ← HIR/MIR/优化/字节码生成
    ↑
nova-interpreter           ← 重命名自 nova-runtime，仅包含解释逻辑
    ↑
┌───┼───────┐
nova-cli  nova-script  nova-lsp
```

**关键改动：**
- 新增 `nova-common`：放置 `@NovaType` 注解和 `BinaryOps` 等共享操作
- `nova-runtime`（解释器）不再 `api` 依赖 `nova-ir`，改为 `implementation`（按需加载 HIR 相关功能）
- `nova-lsp` 只依赖 `nova-compiler` + `nova-common`

### 4.5 合并 stdlib（解决问题 5）

将 `nova-runtime/interpreter/stdlib/` 中的 8 个文件移入 `nova-runtime-api/stdlib/`：

```
nova-runtime-api/stdlib/
├── StdlibRegistry.java  （已有，注册入口）
├── StdlibMath.java      （已有）
├── StdlibIO.java        ← 移入
├── StdlibJson.java      ← 移入
├── StdlibHttp.java      ← 移入
├── StdlibRegex.java     ← 移入
├── StdlibTime.java      ← 移入
├── StdlibSystem.java    ← 移入
├── StdlibConcurrent.java ← 移入
└── StdlibTest.java      ← 移入
```

这样编译后的字节码也能调用 IO/JSON/HTTP 等函数，保证语言行为一致性。

### 4.6 拆分 Lowering 职责（解决问题 6、7）

**AstToHirLowering 拆分为：**
```
AstDesugaring.java          ← 纯脱糖：Elvis→if-null, SafeCall→let-if 等
AstToHirConverter.java      ← 纯结构转换：AST节点 → HIR节点
```

**HirToMirLowering 拆分为：**
```
ControlFlowLowering.java    ← 结构化控制流 → CFG
ClosureLowering.java        ← Lambda/闭包处理
TryCatchLowering.java       ← 异常处理
InteropLowering.java        ← Java 互操作指令生成
```

### 4.7 统一控制流机制（解决问题 8）

**建议：** 统一使用 ControlFlow 异常，移除 ThreadLocal 标志。

```java
// 移除：
interp.getHasReturn() / setHasReturn() / getReturnValue()

// 统一使用：
throw new ControlFlow(ControlFlow.Type.RETURN, value);
```

在函数执行边界 catch ControlFlow 即可，无需维护额外状态。

---

## 五、推荐重构路线图

### 阶段 1：低风险高收益（2-3 周）

1. **提取 BinaryOps/UnaryOps 共享库** → 消除三引擎运算重复
2. **合并 stdlib 到 nova-runtime-api** → 保证编译/解释行为一致
3. **统一控制流为 ControlFlow 异常** → 简化解释器状态管理
4. **清除已弃用的 `Interpreter.java` AST 直接解释代码** → 减少维护面

### 阶段 2：中等改动（3-4 周）

5. **重构模块依赖** → 引入 `nova-common`，打破不合理依赖
6. **拆分 AstToHirLowering** → 脱糖与转换解耦
7. **统一类型系统** → 删除 HirType，使用 NovaType

### 阶段 3：大规模重构（4-6 周）

8. **拆分 HirToMirLowering** → 按职责拆分为 4 个子模块
9. **简化 AST/HIR 双重体系** → 采用方案 A（注解驱动自动转换）或方案 B（统一 IR）
10. **完善优化管线框架** → 添加 Pass 依赖声明、分析缓存

---

## 六、附录：关键文件清单

### 三套执行引擎
| 文件 | 路径 |
|------|------|
| AST 解释器 | `nova-runtime/src/main/java/nova/runtime/interpreter/Interpreter.java` |
| HIR 求值器 | `nova-runtime/src/main/java/nova/runtime/interpreter/HirEvaluator.java` |
| MIR 解释器 | `nova-runtime/src/main/java/nova/runtime/interpreter/MirInterpreter.java` |
| 函数执行 | `nova-runtime/src/main/java/nova/runtime/interpreter/FunctionExecutor.java` |
| 环境 | `nova-runtime/src/main/java/nova/runtime/interpreter/Environment.java` |

### AST 体系
| 文件 | 路径 |
|------|------|
| 访问者接口 | `nova-compiler/src/main/java/com/novalang/compiler/ast/AstVisitor.java` |
| 节点基类 | `nova-compiler/src/main/java/com/novalang/compiler/ast/AstNode.java` |
| 语义分析 | `nova-compiler/src/main/java/com/novalang/compiler/analysis/SemanticAnalyzer.java` |
| 类型推断 | `nova-compiler/src/main/java/com/novalang/compiler/analysis/TypeInferenceEngine.java` |

### HIR/MIR 体系
| 文件 | 路径 |
|------|------|
| HIR 访问者 | `nova-ir/src/main/java/com/novalang/ir/hir/HirVisitor.java` |
| HIR 变换器 | `nova-ir/src/main/java/com/novalang/ir/hir/HirTransformer.java` |
| AST→HIR | `nova-ir/src/main/java/com/novalang/ir/lowering/AstToHirLowering.java` |
| HIR→MIR | `nova-ir/src/main/java/com/novalang/ir/lowering/HirToMirLowering.java` |
| 优化管线 | `nova-ir/src/main/java/com/novalang/ir/pass/PassPipeline.java` |
| 字节码生成 | `nova-ir/src/main/java/com/novalang/ir/backend/MirCodeGenerator.java` |
| IR 编译器 | `nova-ir/src/main/java/com/novalang/ir/NovaIrCompiler.java` |

### 标准库（两处）
| 位置 | 路径 |
|------|------|
| 共享 stdlib | `nova-runtime-api/src/main/java/nova/runtime/stdlib/` |
| 解释器专用 | `nova-runtime/src/main/java/nova/runtime/interpreter/stdlib/` |

### 模块配置
| 文件 | 路径 |
|------|------|
| 根构建 | `build.gradle` |
| 模块声明 | `settings.gradle` |
| compiler | `nova-compiler/build.gradle` |
| ir | `nova-ir/build.gradle` |
| runtime | `nova-runtime/build.gradle` |
| lsp | `nova-lsp/build.gradle` |
| script | `nova-script/build.gradle` |
