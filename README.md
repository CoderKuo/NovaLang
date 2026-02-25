# NovaLang

<p align="center">
  <a href="README.md">English</a> | <a href="README_CN.md">简体中文</a>
</p>

<p align="center">
  <strong>A modern scripting language running on the JVM</strong>
</p>

<p align="center">
  Kotlin-inspired syntax · Seamless Java interop · Async/await · TypeScript-friendly
</p>

<p align="center">
  <a href="#features">Features</a> •
  <a href="#quick-start">Quick Start</a> •
  <a href="#examples">Examples</a> •
  <a href="#documentation">Documentation</a> •
  <a href="#architecture">Architecture</a>
</p>

---

## Features

### Modern Syntax
Kotlin-inspired syntax that's concise, expressive, and easy to learn.

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

### Async/Await Support
Built-in asynchronous programming with `async`/`await`.

```nova
val result = async {
    // concurrent computation
    heavyComputation()
}

println(await result)
```

### Java Interoperability
Seamlessly call Java classes and implement Java interfaces.

```nova
import java java.util.ArrayList
import java java.lang.Runnable

val list = ArrayList<String>()
list.add("Hello")

val runner = object : Runnable {
    fun run() {
        println("Running from Nova!")
    }
}
```

### Rich Type System
- Classes, interfaces, enums, and objects
- Generic types with reified type parameters
- Extension functions
- Nullable types with safe operators (`?.`, `?:`, `!!`)

### Standard Library
Comprehensive stdlib with collections, I/O, JSON, HTTP, and more.

```nova
val numbers = [1, 2, 3, 4, 5]
val doubled = numbers.map { it * 2 }
val filtered = numbers.filter { it > 2 }

val json = parseJson("{\"name\": \"Nova\"}")
println(json["name"])
```

### Multiple Execution Modes
- **REPL**: Interactive shell for experimentation
- **Script**: Execute `.nova` files directly
- **Embedded**: Use as a library in Java/Kotlin applications
- **JSR-223**: Standard scripting engine integration

---

## Quick Start

### Prerequisites
- JDK 8 or higher
- Gradle 7.x (or use the included wrapper)

### Build

```bash
./gradlew build
```

### Run REPL

```bash
./gradlew :nova-cli:run
```

### Execute Script

```bash
./gradlew :nova-cli:run --args="path/to/script.nova"
```

---

## Examples

### Variables & Functions

```nova
val PI = 3.14159
var counter = 0

fun factorial(n: Int): Int {
    if (n <= 1) return 1
    return n * factorial(n - 1)
}

println(factorial(5))  // 120
```

### Control Flow

```nova
// If expression
val result = if (x > 0) "positive" else "non-positive"

// When expression (pattern matching)
fun describe(n: Int): String = when (n) {
    0 -> "zero"
    1, 2 -> "small"
    in 3..10 -> "medium"
    else -> "large"
}

// For loop with range
for (i in 1..10) {
    println(i)
}
```

### Collections

```nova
// List literal
val items = [1, 2, 3, 4, 5]

// Map literal
val person = #{"name": "Alice", "age": 30}

// Set literal
val unique = #{1, 2, 3}

// Higher-order functions
val squared = items.map { it * it }
val evens = items.filter { it % 2 == 0 }
val sum = items.reduce { a, b -> a + b }
```

### Classes & Objects

```nova
class User(val name: String, var age: Int) {
    fun greet(): String = "Hi, I'm $name"
}

val user = User("Alice", 25)
println(user.greet())

// Singleton object
object Database {
    fun connect() { println("Connected") }
}
Database.connect()
```

### Enums

```nova
enum Color {
    RED, GREEN, BLUE
}

fun paint(color: Color) {
    when (color) {
        Color.RED -> println("Painting red")
        else -> println("Painting other")
    }
}
```

### Extension Functions

```nova
fun String.shout(): String = this.toUpperCase() + "!"

val msg = "hello"
println(msg.shout())  // HELLO!
```

### Nullable Types

```nova
val name: String? = maybeNull()

// Safe call
val length = name?.length()

// Elvis operator
val len = name?.length() ?: 0

// Safe index
val first = list?[0]
```

---

## Documentation

| Document | Description |
|----------|-------------|
| [Syntax Specification](docs/语法规范.md) | Complete EBNF grammar |
| [Usage Guide](docs/文档/使用文档.md) | How to use NovaLang |
| [Java Interop](docs/文档/Java互操作指南.md) | Java integration guide |
| [Standard Library](docs/文档/标准库模块.md) | Built-in modules |
| [Reflection API](docs/文档/反射API.md) | Runtime type introspection |
| [Annotation System](docs/文档/注解系统.md) | Custom annotations |

---

## Architecture

```
NovaLang
├── nova-runtime-api    # Core types (NovaValue, NovaClass, etc.)
├── nova-compiler       # Lexer, Parser, AST
├── nova-ir             # HIR/MIR + Optimization passes
├── nova-runtime        # Interpreter + Stdlib
├── nova-cli            # Command-line interface
├── nova-script         # JSR-223 script engine
├── nova-lsp            # Language Server Protocol
└── vscode-nova         # VS Code extension
```

### Compilation Pipeline

```
Source Code → Lexer → Parser → AST → HIR → MIR → Interpreter
                                         ↓
                                   Optimization Passes
                                   (CSE, DCE, Inlining, etc.)
```

---

## VS Code Extension

NovaLang provides a VS Code extension with:
- Syntax highlighting
- Code completion
- Go to definition
- Error diagnostics

Located in `vscode-nova/` directory.

---

## Security

NovaLang includes a configurable security sandbox:

```bash
# Run with sandbox
nova --sandbox STANDARD script.nova

# Sandbox levels
UNRESTRICTED  # No restrictions
STANDARD      # Block dangerous operations
STRICT        # Minimal permissions
```

---

## Project Status

NovaLang is under active development. Current version: `0.1.0-SNAPSHOT`

---

## License

MIT License

---

## Contributing

Contributions are welcome! Please feel free to submit issues and pull requests.
