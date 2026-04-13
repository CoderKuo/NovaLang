# Changelog

本文件记录 NovaLang 各版本的主要变更。

---

## [v0.1.15]

### 错误处理系统重构
- **ErrorKind 分类**：所有运行时异常携带 10 种错误分类（TYPE_MISMATCH / NULL_REFERENCE / UNDEFINED / ARGUMENT_MISMATCH 等）
- **NovaErrors 工厂**：统一构建错误，自动附带修复建议和 Levenshtein 模糊匹配
- **suggestion 提示**：错误消息自动输出 `提示:` 行，建议修复方式
- **源码位置附加**：MirInterpreter catch 块自动从当前指令获取 SourceLocation
- **编译路径行号**：MirCodeGenerator 生成 LineNumberTable + SourceFile 属性，异常堆栈显示 Nova 源码行号
- **全量改造**：runtime-api 和 runtime 模块中 RuntimeException / NullPointerException / ClassCastException 全部清零

### Java 互操作增强
- **List → Array 自动转换**：Nova `[1,2,3]` 传给 Java `int[]` / `String[]` / `Object[]` 参数时自动转换，支持 int/long/double/float/boolean 基本类型数组

### Bug 修复
- 修复 `wrapException` 使用 `getRawMessage()` 避免 catch 块中异常消息包含位置后缀

---

## [v0.1.14]

### Bug 修复
- 修复 `AbstractNovaValue.hashCode` 栈溢出（toJavaValue 返回 this 时无限递归，影响 NovaNamespace/NovaRange）

---

## [v0.1.13]

### Bug 修复
- 修复扩展方法链式调用失效（`ExtensionRegistry.lookupAny` 增加 arity 无关查找 + 继承链遍历）
- 修复 `RegisteredEntry.invoke` 参数数量不匹配时的空指针（优先匹配 + null 填充）
- 增强错误信息：函数调用失败时显示函数名和来源

### 文档
- README 添加 NovaLang logo banner 和 badge

---

## [v0.1.12]

### 新特性
- **语法增强**：尾随逗号容忍、Map key 支持变量引用、`as` 运算符优先级提升（高于算术）、`as` 数值类型转换、`[]` 内支持 as/is
- **标准库大幅扩展**：
  - 3 个新 import 模块：`nova.encoding`（Base64/URL/Hex/Base62）、`nova.crypto`（MD5/SHA/UUID/Snowflake/NanoID）、`nova.yaml`
  - 扩展函数补全：Set 20+ 方法、Boolean 6 方法、Char 3 方法、Number 4 方法、List 7 方法、String 正则 9 方法
  - Hutool 风格工具函数：truncate、isNumber、similar、countMap、disjunction、randomEle/shuffle/weightRandom、round/formatPercent
  - 集合增强：Triple、listOfNotNull、sortedMapOf、sortedSetOf、Range step/downTo
- **JSON/YAML SPI**：ServiceLoader 多 provider 架构，4 个独立 Gradle 模块（nova-json-gson/fastjson2、nova-yaml-snakeyaml/bukkit）
- **Shared 全局注册表**：完整 CRUD API（sharedLibraries/Functions/Register/Set/Remove/Has/Get）
- **Java 互操作**：反射函数（javaFields/Methods/Superclass/Interfaces）、NovaDynamicObject 接口、MemberNameResolver 回调、inner class 访问、Double→float 兼容
- **文件注解系统** + 脚本级 ClassLoader 隔离
- **MIR 性能优化** + Lambda with Receiver DSL + javaClass 常量传播
- **null as T? 安全转换**：`null as String?` 返回 null 而非抛异常

### Bug 修复
- URLEncoder/URLDecoder 兼容 Java 8
- VirtualMethodDispatcher 中文乱码注释修复

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
