# Java 互操作指南

## 概述

NovaLang 运行在 JVM 上，可以直接调用 Java 类库。通过 `Java` 命名空间和 `javaClass()` 函数，你可以创建 Java 对象、调用方法、访问字段，实现与 Java 生态的无缝互操作。

> 注意：Java 互操作在 STRICT 沙箱模式下被完全禁用，在 STANDARD 模式下受限使用。

## Java 命名空间

### Java.type(className)

获取 Java 类引用，返回的对象可直接作为构造函数调用：

```nova
val ArrayList = Java.type("java.util.ArrayList")
val list = ArrayList()    // 创建 ArrayList 实例
list.add("hello")
println(list.size())      // 1
```

### Java.new(className, args...)

创建 Java 对象实例：

```nova
val list = Java.new("java.util.ArrayList")
val sb = Java.new("java.lang.StringBuilder", "Hello")
```

等价于 `Java.type(className)(args...)`。

### Java.static(className, methodName, args...)

调用 Java 静态方法：

```nova
val time = Java.static("java.lang.System", "currentTimeMillis")
println(time)

val maxVal = Java.static("java.lang.Math", "max", 10, 20)
println(maxVal)  // 20
```

### Java.field(className, fieldName)

获取 Java 静态字段值：

```nova
val pi = Java.field("java.lang.Math", "PI")
println(pi)  // 3.141592653589793

val maxInt = Java.field("java.lang.Integer", "MAX_VALUE")
println(maxInt)  // 2147483647
```

### Java.isInstance(obj, className)

检查对象是否是指定 Java 类的实例：

```nova
val list = Java.new("java.util.ArrayList")
println(Java.isInstance(list, "java.util.List"))       // true
println(Java.isInstance(list, "java.util.ArrayList"))   // true
println(Java.isInstance(list, "java.util.Map"))         // false
```

### Java.class(obj)

获取对象的 Java 类名：

```nova
val list = Java.new("java.util.ArrayList")
println(Java.class(list))  // java.util.ArrayList
```

## javaClass() 函数

简便方式获取 Java 类引用，效果与 `Java.type()` 相同：

```nova
val ArrayList = javaClass("java.util.ArrayList")
val list = ArrayList()
```

## 自动包搜索

使用 `Java.type()` 时，如果类名不包含完整包路径，NovaLang 会自动在以下包中搜索：

- `java.lang.`
- `java.util.`
- `java.io.`
- `java.nio.`
- `java.math.`
- `java.time.`
- `java.util.function.`
- `java.util.stream.`
- `java.util.concurrent.`

```nova
// 以下写法等价：
val list = Java.type("java.util.ArrayList")()
val list = Java.type("ArrayList")()  // 自动在 java.util 中找到
```

## 类型转换

### Nova → Java

NovaLang 值传递给 Java 方法时自动转换：

| Nova 类型 | Java 类型 |
|-----------|-----------|
| `Int` | `int` / `Integer` |
| `Long` | `long` / `Long` |
| `Double` | `double` / `Double` |
| `Float` | `float` / `Float` |
| `Boolean` | `boolean` / `Boolean` |
| `String` | `String` |
| `Char` | `char` / `Character` |
| `List` | `List` |
| `Map` | `Map` |
| `null` | `null` |

### Java → Nova

Java 方法返回值自动转换为 Nova 类型：

| Java 类型 | Nova 类型 |
|-----------|-----------|
| `null` | `null` |
| `Integer` | `Int` |
| `Long` | `Long` |
| `Double` | `Double` |
| `Float` | `Float` |
| `Boolean` | `Boolean` |
| `String` | `String` |
| `Character` | `Char` |
| `int[]` / `double[]` 等原生数组 | `Array` |
| `Object[]` | `Array` |
| `List` | `List` |
| `Map` | `Map` |
| 其他对象 | `NovaExternalObject`（包装类） |

## SAM 转换

NovaLang 的 Lambda 可自动转换为 Java 的单一抽象方法（SAM）接口：

| Java 接口 | Lambda 签名 |
|-----------|-------------|
| `Runnable` | `{ -> ... }` |
| `Supplier<T>` | `{ -> value }` |
| `Consumer<T>` | `{ item -> ... }` |
| `Function<T, R>` | `{ input -> output }` |
| `Predicate<T>` | `{ item -> boolean }` |
| `Comparator<T>` | `{ a, b -> int }` |

```nova
val list = Java.new("java.util.ArrayList")
list.add(3)
list.add(1)
list.add(2)

// Lambda 自动转换为 Comparator
list.sort { a, b -> a - b }
println(list)  // [1, 2, 3]
```

## 使用示例

### 集合操作

```nova
// ArrayList
val list = Java.type("java.util.ArrayList")()
list.add("Apple")
list.add("Banana")
list.add("Cherry")
println(list.size())    // 3
println(list.get(1))    // Banana
list.remove("Banana")

// HashMap
val map = Java.type("java.util.HashMap")()
map.put("name", "Alice")
map.put("age", 30)
println(map.get("name"))         // Alice
println(map.containsKey("age"))  // true

// LinkedList
val queue = Java.type("java.util.LinkedList")()
queue.add("first")
queue.add("second")
println(queue.peek())   // first
println(queue.poll())   // first
```

### 字符串处理

```nova
val sb = Java.type("java.lang.StringBuilder")()
sb.append("Hello")
sb.append(" ")
sb.append("World")
sb.insert(5, ",")
println(sb.toString())  // Hello, World
println(sb.length())    // 12
```

### 日期时间

```nova
// 当前日期
val date = Java.type("java.util.Date")()
println(date)

// LocalDateTime
val now = Java.static("java.time.LocalDateTime", "now")
println(now)

// 格式化
val formatter = Java.static("java.time.format.DateTimeFormatter", "ofPattern", "yyyy-MM-dd")
println(now.format(formatter))
```

### 数学计算

```nova
// BigDecimal 精确计算
val BigDecimal = Java.type("java.math.BigDecimal")
val a = BigDecimal("0.1")
val b = BigDecimal("0.2")
println(a.add(b))  // 0.3

// BigInteger
val BigInteger = Java.type("java.math.BigInteger")
val big = BigInteger("123456789012345678901234567890")
println(big.multiply(BigInteger("2")))
```

### 文件操作

```nova
// 注意：文件操作在 STANDARD/STRICT 沙箱下被禁止
val File = Java.type("java.io.File")
val file = File("data.txt")
println(file.exists())
println(file.getName())
println(file.getAbsolutePath())
```

## 成员访问规则

### 方法调用

Java 对象的方法可直接调用：

```nova
val list = Java.new("java.util.ArrayList")
list.add("item")           // 调用实例方法
list.size()                 // 调用无参方法
list.get(0)                 // 带参数调用
```

### 静态成员

通过 `Java.type()` 获取类后访问静态成员：

```nova
val Math = Java.type("java.lang.Math")
println(Math.PI)            // 静态字段
println(Math.max(3, 5))     // 静态方法
```

或使用 `Java.static()` 和 `Java.field()`：

```nova
val pi = Java.field("java.lang.Math", "PI")
val max = Java.static("java.lang.Math", "max", 3, 5)
```

## 安全限制

不同沙箱级别对 Java 互操作的限制：

### UNRESTRICTED

无任何限制，可访问所有 Java 类和方法。

### STANDARD

- **允许的包**：java.util、java.math、java.time、java.text、java.lang
- **禁止的包**：java.io、java.nio、java.net、java.lang.reflect
- **禁止的类**：Runtime、ProcessBuilder、ClassLoader、Thread
- **禁止的方法**：System.exit、System.load、System.loadLibrary

```nova
// STANDARD 模式下：
Java.type("java.util.ArrayList")()    // OK
Java.type("java.io.File")            // 错误！Security policy denied
Java.static("java.lang.System", "exit", 0)  // 错误！方法被禁止
```

### STRICT

完全禁止 Java 互操作：

```nova
// STRICT 模式下：
Java.type("java.util.ArrayList")  // 错误！Java interop not allowed
javaClass("java.util.ArrayList")  // 错误！Java interop not allowed
```

### 自定义安全策略

在嵌入式使用场景中，可通过 Java API 创建自定义策略：

```java
NovaSecurityPolicy policy = NovaSecurityPolicy.custom()
    .allowJavaInterop(true)
    .allowPackage("java.util")
    .allowPackage("java.math")
    .denyClass("java.lang.Runtime")
    .denyMethod("java.lang.System", "exit")
    .maxExecutionTime(5000)
    .build();

Interpreter interpreter = new Interpreter(policy);
```

类加载检查遵循 **deny 优先** 原则：

1. 类在黑名单 → 拒绝
2. 类在白名单 → 允许
3. 类所在包在黑名单 → 拒绝
4. 包白名单非空且不在白名单中 → 拒绝
5. 以上都不匹配 → 允许
