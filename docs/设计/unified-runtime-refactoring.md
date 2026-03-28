# 统一运行时层全面重构方案

## 问题

当前架构中 `interpreterMode` 标志导致 HirToMirLowering 产出两种 MIR 方言，每个运行时概念都有双重实现：
- 函数注册：Builtins ↔ StdlibCore
- 成员分派：MemberResolver ↔ NovaDynamic
- 二元运算：BinaryOps ↔ NovaOps
- 索引操作：MemberResolver.performIndex ↔ NovaCollections

新功能维护成本 2x，bug 通常只在一条路径上被修复。

## 目标架构

```
            源码
             │
         Parser → AST
             │
       AstToHirLowering
             │
       HirToMirLowering  ← 无 interpreterMode，只产出一种 MIR
             │
         MIR（唯一语义）
            / \
           /   \
  MirInterpreter  MirCodeGenerator
  (dev/REPL)      (release/性能)
           \   /
            \ /
       NovaRuntime（统一运行时层）
    ┌─────────────────────────┐
    │ 函数注册: register()     │  ← 替代 Builtins + StdlibCore + StdlibRegistry
    │ 成员分派: resolveMember()│  ← 替代 MemberResolver + NovaDynamic
    │ 二元运算: binaryOp()     │  ← 替代 BinaryOps + NovaOps
    │ 索引操作: index/setIndex │  ← 替代 NovaCollections 零散方法
    └─────────────────────────┘
```

## 删除清单

| 要删的 | 替代者 | 理由 |
|--------|--------|------|
| `interpreterMode` 标志 | 不需要 | MIR 只有一种语义 |
| `NovaOps.java` | `BinaryOps.java` | 运算逻辑统一 |
| `StdlibCore.java` 的函数注册 | `NovaRuntime` | 函数注册统一 |
| `Builtins.java` 的函数注册 | `NovaRuntime` | 函数注册统一 |
| `NovaDynamic` 的大部分 | `NovaValue.resolveMember()` | 成员分派统一 |
| `HirEvaluator.java` | MirInterpreter | 旧 HIR 解释器已无用 |
| `NovaScriptContext` 大部分 | `NovaRuntime` | 运行时上下文统一 |
| `StaticMethodDispatcher` 的拦截逻辑 | `NovaRuntime.call()` | 静态方法分派统一 |

---

## Phase 1：统一函数注册（消除双注册）

### 目标
新增内置函数只需在一个地方注册，解释器和编译器路径自动可用。

### 步骤

1. **创建 `NovaRuntime.java`**（nova-runtime-api 模块）
   - 持有全局函数表 `Map<String, NativeFunction>`
   - `register(name, arity, impl)` 注册函数
   - `call(name, args)` 调用函数（两条路径共用）
   - ThreadLocal 上下文支持多实例隔离

2. **迁移 StdlibCore 注册逻辑**
   - StdlibCore 的 `register()` 方法中所有 `StdlibRegistry.register(new NativeFunctionInfo(...))` 改为 `NovaRuntime.register(...)`
   - StdlibCore 的静态方法实现保留（作为 NovaRuntime 的 impl 委托目标）
   - 删除 StdlibCore 中与 Builtins 重复的注册

3. **迁移 Builtins 注册逻辑**
   - Builtins 中不依赖 Interpreter 的函数（IntArray/arrayOf/Pair/range 等）迁移到 NovaRuntime
   - 依赖 Interpreter 的函数（typeof/isCallable/len/toXxx 等）暂时保留在 Builtins，后续 Phase 处理

4. **StaticMethodDispatcher 改为查 NovaRuntime**
   - `handleStaticCall()` 中的 NovaCollections 拦截改为 `NovaRuntime.call()`
   - 删除与 StdlibCore 重复的分派逻辑

5. **NovaScriptContext.call() 委托 NovaRuntime**
   - `NovaScriptContext.call(name, args)` 先查自身 bindings，再查 `NovaRuntime.call(name, args)`

### 验证
- 所有现有测试通过
- IntArray/arrayOf 等只注册一次
- 新增函数只需一处 NovaRuntime.register()

---

## Phase 2：统一成员分派（消除 MemberResolver ↔ NovaDynamic 重复）

### 目标
类型的成员访问逻辑由类型自身实现（`resolveMember()`），消除外部分派器的类型 instanceof 链。

### 步骤

1. **扩展 NovaValue.resolveMember(String)**
   - 已有 default 实现返回 null（Phase 0 中已为 NovaPair 实现）
   - 为以下类型实现 resolveMember：
     - NovaList：size/first/last/isEmpty 等属性
     - NovaMap：size/keys/values/isEmpty 等属性
     - NovaString：length/isEmpty 等属性
     - NovaArray：size/length 属性
     - NovaResult：value/error/isOk/isErr 属性
     - NovaRange：start/end/step 属性
     - NovaObject：字段 → 方法 → 静态字段

2. **添加 NovaValue.invokeMember(String, NovaValue[])**
   - 方法调用的统一入口
   - 默认实现：`resolveMember(name)` → 如果是 NovaCallable 则调用
   - NovaObject 覆写：查找方法 + 扩展方法

3. **简化 MemberResolver**
   - resolveBuiltinMember() 改为 `obj.resolveMember(name)` + 扩展函数 fallback
   - resolveObjectMember() 委托 `novaObj.resolveMember(name)`
   - 目标：从 ~500 行缩减到 ~150 行

4. **简化 NovaDynamic**
   - getMember() 改为 `target.resolveMember(name)` + Java 反射 fallback
   - invokeMethod() 改为 `target.invokeMember(name, args)` + Java 反射 fallback
   - 目标：从 ~1700 行缩减到 ~500 行

5. **MirInterpreter executeGetField 简化**
   - NovaObject 快速路径保留（性能关键）
   - 其他类型统一走 `target.resolveMember(fieldName)`

### 验证
- MemberResolver < 200 行
- NovaDynamic < 600 行
- NovaPair/NovaList/NovaMap 的成员访问在两条路径行为一致

---

## Phase 3：消除 interpreterMode（MIR 统一）

### 目标
HirToMirLowering 只产出一种 MIR，删除所有 `if (interpreterMode)` 分支。

### 步骤

1. **审计所有 interpreterMode 分支**
   - 搜索 HirToMirLowering 中所有 `interpreterMode` 使用（约 60 处）
   - 分类：
     - A类：生成不同调用指令（INVOKE_VIRTUAL vs INVOKESTATIC）→ 统一为 INVOKE_VIRTUAL
     - B类：不同的类型解析逻辑 → 统一使用 typeToInternalName
     - C类：不同的方法描述符 → 统一使用运行时分派

2. **统一方法调用生成**
   - 对未知类型的方法调用，统一生成 INVOKE_VIRTUAL
   - MirInterpreter：通过 resolveMember + invokeMember 分派
   - MirCodeGenerator：生成 invokedynamic，bootstrap 调 resolveMember

3. **统一全局函数调用生成**
   - 已注册的 NovaRuntime 函数：生成 INVOKE_STATIC NovaRuntime.call(name, args)
   - 用户定义的顶层函数：生成 INVOKE_STATIC $Module.funcName(args)（现有逻辑保留）

4. **统一二元运算**
   - 类型已知（Int×Int 等）：生成 IADD/ISUB 等（现有优化保留）
   - 类型未知：生成 INVOKE_STATIC BinaryOps.add(Object, Object)
   - 删除 NovaOps.java，BinaryOps 同时处理 NovaValue 和 raw Object

5. **删除 interpreterMode 标志**
   - HirToMirLowering 中删除 `private boolean interpreterMode` 字段
   - 删除 `setInterpreterMode()` 方法
   - PassPipeline 中删除 interpreterMode 传递

### 验证
- `grep -r "interpreterMode" nova-ir/` 返回 0 结果
- MIR dump 对同一源码只有一种输出
- 所有测试通过

---

## Phase 4：删除 HirEvaluator + 清理

### 目标
删除旧 HIR 解释器和所有仅为它保留的代码。

### 步骤

1. 删除 `HirEvaluator.java`
2. 删除 `--legacy` CLI 标志及相关逻辑
3. 清理 Interpreter.java 中仅为 HirEvaluator 保留的方法
4. 清理 NovaScriptContext 中被 NovaRuntime 取代的逻辑
5. 删除 NovaOps.java（如果 Phase 3 未删除）
6. 清理未使用的 import 和死代码

### 验证
- 代码量净减约 2000+ 行
- 所有测试通过
- `nova eval` 和 `nova run` 行为不变

---

## 风险控制

- 每个 Phase 独立提交 + tag，随时可回退
- Phase 1-2 不破坏外部 API（Nova.java 的 public 方法签名不变）
- Phase 3 风险最高（改变 MIR 语义），需要完整测试覆盖
- Phase 4 纯删除，风险最低

## 预期收益

| 指标 | 现在 | 重构后 |
|------|------|--------|
| 新增内置函数的改动点 | 2-3 个文件 | 1 个文件 |
| 新增类型成员的改动点 | 3-5 个文件 | 1 个文件（类型自身） |
| interpreterMode 分支数 | ~60 处 | 0 |
| 运行时辅助类 | BinaryOps + NovaOps + NovaCollections + NovaDynamic | BinaryOps + NovaRuntime |
| MIR 方言数 | 2 | 1 |
