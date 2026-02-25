# NovaLang

<p align="center">
  <strong>运行在 JVM 上的现代脚本语言</strong>
</p>

<p align="center">
  Kotlin 风格语法 · 无缝 Java 互操作 · Async/Await · 类型安全
</p>

<p align="center">
  <a href="#特性">特性</a> •
  <a href="#快速开始">快速开始</a> •
  <a href="#示例">示例</a> •
  <a href="#文档">文档</a> •
  <a href="#架构">架构</a>
</p>

<p align="center">
  <a href="README.md">English</a> | <b>简体中文</b>
</p>

---

## 特性

### 现代语法
借鉴 Kotlin 的简洁优雅语法，易于学习和使用。

```nova
val name = "Nova"
var count = 0

fun greet(who: String): String {
    return "Hello, $who!"
}

class Point(val x: Int, val y: Int) {
    fun distance(): Double = sqrt(x * x + y * y)
}
```

### 异步编程支持
内置 `async`/`await` 异步编程模型。

```nova
val result = async {
    // 并发计算
    heavyComputation()
}

println(await result)
```

### Java 互操作
无缝调用 Java 类和实现 Java 接口。

```nova
import java java.util.ArrayList
import java java.lang.Runnable

val list = ArrayList<String>()
list.add("Hello")

val runner = object : Runnable {
    fun run() {
        println("从 Nova 运行！")
    }
}
```

### 丰富的类型系统
- 类、接口、枚举、单例对象
- 泛型与具化类型参数
- 扩展函数
- 空安全类型（`?.`、`?:`、`!!`）

### 标准库
完善的内置库，包含集合、I/O、JSON、HTTP 等模块。

```nova
val numbers = [1, 2, 3, 4, 5]
val doubled = numbers.map { it * 2 }
val filtered = numbers.filter { it > 2 }

val json = parseJson("{\"name\": \"Nova\"}")
println(json["name"])
```

### 多种执行模式
- **REPL**：交互式命令行
- **脚本**：直接执行 `.nova` 文件
- **嵌入式**：作为 Java/Kotlin 库使用
- **JSR-223**：标准脚本引擎集成

---

## 快速开始

### 环境要求
- JDK 8 或更高版本
- Gradle 7.x（或使用自带的 wrapper）

### 构建

```bash
./gradlew build
```

### 运行 REPL

```bash
./gradlew :nova-cli:run
```

### 执行脚本

```bash
./gradlew :nova-cli:run --args="path/to/script.nova"
```

---

## 示例

### 变量与函数

```nova
val PI = 3.14159
var counter = 0

fun factorial(n: Int): Int {
    if (n <= 1) return 1
    return n * factorial(n - 1)
}

println(factorial(5))  // 120
```

### 控制流

```nova
// if 表达式
val result = if (x > 0) "正数" else "非正数"

// when 表达式（模式匹配）
fun describe(n: Int): String = when (n) {
    0 -> "零"
    1, 2 -> "小"
    in 3..10 -> "中"
    else -> "大"
}

// for 循环与区间
for (i in 1..10) {
    println(i)
}
```

### 集合

```nova
// 列表字面量
val items = [1, 2, 3, 4, 5]

// Map 字面量
val person = #{"name": "Alice", "age": 30}

// Set 字面量
val unique = #{1, 2, 3}

// 高阶函数
val squared = items.map { it * it }
val evens = items.filter { it % 2 == 0 }
val sum = items.reduce { a, b -> a + b }
```

### 类与对象

```nova
class User(val name: String, var age: Int) {
    fun greet(): String = "你好，我是 $name"
}

val user = User("Alice", 25)
println(user.greet())

// 单例对象
object Database {
    fun connect() { println("已连接") }
}
Database.connect()
```

### 枚举

```nova
enum Color {
    RED, GREEN, BLUE
}

fun paint(color: Color) {
    when (color) {
        Color.RED -> println("涂红色")
        else -> println("涂其他颜色")
    }
}
```

### 扩展函数

```nova
fun String.shout(): String = this.toUpperCase() + "!"

val msg = "hello"
println(msg.shout())  // HELLO!
```

### 空安全

```nova
val name: String? = maybeNull()

// 安全调用
val length = name?.length()

// Elvis 运算符
val len = name?.length() ?: 0

// 安全索引
val first = list?[0]
```

---

## 文档

| 文档 | 说明 |
|------|------|
| [语法规范](docs/语法规范.md) | 完整 EBNF 语法定义 |
| [使用文档](docs/文档/使用文档.md) | NovaLang 使用指南 |
| [Java 互操作](docs/文档/Java互操作指南.md) | Java 集成指南 |
| [标准库模块](docs/文档/标准库模块.md) | 内置模块参考 |
| [反射 API](docs/文档/反射API.md) | 运行时类型反射 |
| [注解系统](docs/文档/注解系统.md) | 自定义注解 |

---

## 架构

```
NovaLang
├── nova-runtime-api    # 核心类型（NovaValue、NovaClass 等）
├── nova-compiler       # 词法分析、语法解析、AST
├── nova-ir             # HIR/MIR + 优化 Pass
├── nova-runtime        # 解释器 + 标准库
├── nova-cli            # 命令行工具
├── nova-script         # JSR-223 脚本引擎
├── nova-lsp            # 语言服务器协议
└── vscode-nova         # VS Code 插件
```

### 编译管线

```
源代码 → 词法分析 → 语法解析 → AST → HIR → MIR → 解释执行
                                    ↓
                              优化 Pass
                           (CSE、DCE、内联等)
```

---

## VS Code 插件

NovaLang 提供完整的 VS Code 支持：
- 语法高亮
- 代码补全
- 跳转定义
- 错误诊断

插件位于 `vscode-nova/` 目录。

---

## 安全沙箱

NovaLang 内置可配置的安全沙箱：

```bash
# 启用沙箱运行
nova --sandbox STANDARD script.nova

# 沙箱级别
UNRESTRICTED  # 无限制
STANDARD      # 阻止危险操作
STRICT        # 最小权限
```

---

## 项目状态

NovaLang 正在积极开发中。当前版本：`0.1.0-SNAPSHOT`

---

## 许可证

MIT License

---

## 参与贡献

欢迎提交 Issue 和 Pull Request！
