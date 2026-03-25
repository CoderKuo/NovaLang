# Changelog

本文件记录 NovaLang 各版本的主要变更。

---

## [v0.1.11]

### 新特性
- **基于名称的解构声明**：支持 `val (localVar = propertyName, ...) = expr` 语法，按属性名而非位置解构，顺序无关
  ```nova
  @data class User(val username: String, val email: String)
  val user = User("alice", "alice@example.com")
  val (mail = email, name = username) = user  // 按名称，顺序任意
  ```
  - 支持混合模式（名称 + 位置）
  - 支持 for 循环：`for ((cost = price) in items) { ... }`
  - 解释器和编译器双路径均已支持
- **`check()` / `require()` 断言函数**
- **`Result.getOrElse { }` / `Result.flatMap { }`** 方法
- **`Enum.entries`** 属性（返回枚举条目列表）
- **Reflect API**：`field.isMutable`、`method.parameters` 属性
- **`data object`**：`@data object Error` 自动生成一致的 `toString`（返回纯类名），常与 `sealed interface` 配合表示无数据状态分支
- **when Guard Conditions**：`is Type if condition -> body` 语法，条件匹配且 guard 为真时才进入分支
  ```nova
  when (animal) {
      is Cat if !animal.isHungry -> "feed cat"
      is Cat -> "hungry cat"
      else -> "unknown"
  }
  ```
  - 支持所有条件类型（is/in/值匹配/无 subject）+ 任意 guard 表达式
  - 支持多条件 + guard：`1, 2 if flag -> ...`
  - 解释器和编译器双路径均已支持
- **接收者函数类型（Lambda with Receiver）**：支持 `T.() -> R` 类型声明语法
  - 参数声明：`fun configure(block: Config.() -> Unit)`
  - `receiver.block()` 自动绑定作用域接收者
- **Builder DSL Java API**：`Nova.defineBuilderFunction()` / `Nova.invokeWithReceiver()`
  - `defineBuilderFunction(name, factory)` — 定义 builder 风格函数
  - `invokeWithReceiver(callable, receiver)` — 以接收者调用 Nova lambda
- **MIR 循环死存储消除**（LoopDeadStoreElimination Pass）：循环内冗余的 SET_STATIC + NovaScriptContext.set 下沉到循环出口
  - arith_loop: **4.6x 提速**（17.7ms → 3.9ms）
  - call_loop: **2.7x 提速**（12.4ms → 4.6ms）
- **SET_STATIC/GET_STATIC 缓存优化**：StaticFieldSite 缓存消除每次调用的字符串解析开销
- **`javaClass()` 编译期常量传播**：`val X = javaClass("java.lang.Integer")` → `X.MAX_VALUE` 直接编译为 GETSTATIC（11.5µs，与 Java 原生持平）
- **API Server 端口冲突修复**：多 JVM 环境下自动递增端口重试
- **Non-local break/continue**：在 `forEach` 等 lambda 内使用 `break`/`continue`，跳出外层迭代
  ```nova
  list.forEach { if (it == 0) continue; process(it) }
  list.forEach { if (it == target) break }
  ```
  - 支持 `forEach`、`forEachIndexed`、Map `forEach`
  - 嵌套 forEach 中 break 只影响最内层

### 修复
- MirCodeGenerator：`unboxBoolean` / Branch 终止器未检查 `intLocals` 导致 VerifyError
- MirCodeGenerator：`isLocalUsedInOtherBlocks` 遗漏 fused Branch 引用
- MirCodeGenerator：`unboxInt` 未兼容 Boolean 类型和 null 值
- 多层继承 `super` 调用导致 StackOverflowError
- `with()` 作用域函数未正确绑定 scope receiver
- `HirConstantFolding`：`"abc" * 0` 被错误优化为 `0` 而非 `""`
- `StrengthReduction`：`Vec(2,3) * 4` 被错误转换为位移操作
- `MirCodeGenerator`：编译模式下 `"ab" * 3` ClassCastException
- 跨 REPL 命名参数传递丢失
- 自定义 getter/setter 在 MIR 路径不生效
- 泛型嵌套 `>>` 被词法分析器误解析为右移运算符

### 重构
- 彻底删除 HirEvaluator（1607 行），MIR 管线完全取代 HIR 解释器
- 删除 HirFunctionValue、HirLambdaValue、ControlFlow、ScopeTracker、HirVariableResolver 等遗留代码
- FunctionExecutor 精简：736 → 204 行
- 测试覆盖率从 52%/43% 提升至 66%/56%（4000+ 测试）

---

## [v0.1.10] - 2026-03-22

### 新特性
- NovaRuntime 全局注册表重构
- SAM 适配器（Java 函数式接口自动适配）
- HTTP API 服务（NovaApiServer）
- VS Code 运行时自动补全

### 修复
- Int/Double 混合比较 ClassCastException
- Lambda 闭包顶层变量 NoSuchFieldError

---

## [v0.1.7] - 2026-03-16

### 新特性
- 编译模式安全策略（UNRESTRICTED/STANDARD/STRICT/CUSTOM）
- 顶层变量静态字段化
- Java 互操作优化

---

## [v0.1.6] - 2026-03-15

### 重构
- 解构/重复逻辑统一收口
- 编译器修复 + 测试补全
- 统一包路径 `nova.*` → `com.novalang.*`
- NovaDynamic 反射优化
- Map 字面量 key 修复

---

## [v0.1.5] - 2026-03-14

### 新特性
- 默认参数 / 命名参数编译支持
- 多异常捕获 `catch (e: IOException | ParseException)`
- Sealed class 穷举检查
- 新增语言特性 + 错误报告增强

---

## [v0.1.3] - 2026-03-12

### 重构
- 调用解析管线重构（call resolution pipeline）

### 新增
- GraalJS、Groovy、JEXL 引擎性能对比基准测试

---

## [v0.1.2] - 2026-03-11

### 新特性
- `invokedynamic` bootstrap（单态内联缓存）
- 类型安全 Java 互操作 API（`NovaValue.toJava(Class<T>)`）
- LSP YAML 内嵌 Nova 支持

---

## [v0.1.1] - 2026-03-10

### 新特性
- NovaLibrary / NovaExt 注解式函数库系统
- Function4-8 接口 + defineFunction API
- Unicode 标识符支持

### 重构
- NovaValue 接口化 + 类型层级
- MirCallDispatcher 拆分为 Virtual / Static 分派器
- 提取 nova-runtime-types 模块
- Caffeine 类路径重定位（避免 Bukkit ClassLoader 冲突）

---

## [v0.1.0] - 2026-03-09

- 初始版本：NovaLang JVM 脚本语言引擎
- GitHub Actions 发布工作流
