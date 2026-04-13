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

数值类型自动宽化/窄化：`Double` 可传递给 `float` 参数，`Int` 可传递给 `long` 参数等。

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

### 数组访问

Java 数组支持 `.length` 属性和索引访问：

```nova
val arr = toJavaArray([1, 2, 3])
println(arr.length)         // 3
println(arr[0])             // 1
```

## 反射查询

内置函数提供对 Java 类/对象的反射能力，无需手动导入 `java.lang.reflect`。

参数可以是**对象实例**（查询其运行时类型）或**类名字符串**（含 `.` 视为全限定名）。

```nova
// 查询方法列表
val methods = javaMethods("hello")
println(methods)  // [length, isEmpty, charAt, substring, toUpperCase, ...]

// 查询字段
val fields = javaFields("java.lang.Integer")
println(fields)  // [MAX_VALUE, MIN_VALUE, TYPE, SIZE, BYTES]

// 继承关系
println(javaSuperclass(42))       // java.lang.Number
println(javaInterfaces(42))       // [java.lang.Comparable]
println(javaSuperclass("java.lang.Object"))  // null

// 类型判断
println(javaTypeName(3.14))                           // java.lang.Double
println(javaInstanceOf([1, 2], "java.util.List"))     // true
println(javaInstanceOf("hello", "java.lang.Number"))  // false
println(javaInstanceOf(null, "java.lang.String"))     // false（null 安全）
```

## 集合互转

将 Nova 集合显式转换为 Java 标准集合类型，适用于需要传递给 Java API 的场景：

```nova
// Nova List → java.util.ArrayList
val jList = toJavaList([1, 2, 3])
jList.add(4)  // 可变操作

// Nova Map → java.util.HashMap
val jMap = toJavaMap(#{"host": "localhost", "port": 8080})
jMap.put("timeout", 3000)

// Nova Collection → java.util.HashSet（自动去重）
val jSet = toJavaSet([1, 2, 2, 3, 3])
println(jSet.size())  // 3

// Nova List → Object[]（支持 .length）
val jArr = toJavaArray(["a", "b", "c"])
println(jArr.length)  // 3
```

## 动态属性对象

Java 对象实现 `NovaDynamicObject` 接口后，Nova 的点号访问和赋值委托给接口方法，而非 Java 反射：

```java
public class PlayerData implements NovaDynamicObject {
    private final Map<String, Object> data = new HashMap<>();
    public Object getMember(String name) { return data.get(name); }
    public void setMember(String name, Object value) { data.put(name, value); }
    public boolean hasMember(String name) { return data.containsKey(name); }
}
```

```nova
// Nova 脚本
playerData.hp = 100
playerData.name = "Steve"
println(playerData.hp)  // 100
```

## 自定义成员名称映射

通过 `MemberNameResolver` 可在成员查找失败时自动映射名称（用于 MCP 混淆映射等）：

```java
Nova.setMemberResolver((targetClass, memberName, isMethod) -> {
    if (isMethod) return McpMappingResolver.resolveMethod(memberName);
    else return McpMappingResolver.resolveField(memberName);
});
```

脚本中使用可读名称，自动映射为混淆后的实际名称：
```nova
val hp = entity.health  // 自动映射 health → field_a
entity.setHealth(100)    // 自动映射 setHealth → method_b
```

## List → Java 数组自动转换

当 Nova 的 List 传递给 Java 方法的数组参数时，运行时会自动完成类型转换，无需手动调用 `toJavaArray()`。

### 支持的数组类型

| 目标 Java 参数类型 | Nova 值示例 |
|-------------------|-------------|
| `int[]` | `[1, 2, 3]` |
| `long[]` | `[1L, 2L, 3L]` |
| `double[]` | `[1.0, 2.0, 3.0]` |
| `float[]` | `[1.0, 2.0]` |
| `boolean[]` | `[true, false]` |
| `String[]` | `["a", "b", "c"]` |
| `Object[]` | 任意列表 |

### 使用示例

```nova
// 假设 Java 方法签名: void process(int[] data)
val processor = Java.type("com.example.DataProcessor")()
processor.process([1, 2, 3, 4, 5])  // List 自动转为 int[]

// 假设 Java 方法签名: String join(String[] parts)
val joiner = Java.type("com.example.StringJoiner")()
val result = joiner.join(["hello", "world"])  // List 自动转为 String[]
```

转换规则：
- 当方法参数类型为数组（`T[]`）而传入的是 Nova List 时，自动逐元素转换
- 基本类型数组（`int[]`、`double[]` 等）有快速路径优化
- NovaArray 类型如果底层数组已匹配目标类型，直接传递（零拷贝）
- varargs 参数的最后一个数组参数由 JVM 自动处理，不做额外转换

## 错误处理系统

NovaLang 提供结构化的错误处理，包括错误分类、修复建议和源码位置追踪。

### 错误分类（ErrorKind）

所有运行时错误按以下类别分类：

| ErrorKind | 说明 |
|-----------|------|
| `TYPE_MISMATCH` | 类型不匹配（如 `as`/`is` 转换失败） |
| `NULL_REFERENCE` | 空指针访问 |
| `UNDEFINED` | 未定义的变量/函数/成员 |
| `ARGUMENT_MISMATCH` | 参数数量或类型不匹配 |
| `ACCESS_DENIED` | 安全策略拒绝访问 |
| `INDEX_OUT_OF_BOUNDS` | 索引越界 |
| `JAVA_INTEROP` | Java 互操作错误 |
| `IO_ERROR` | I/O 操作失败 |
| `PARSE_ERROR` | 语法解析错误 |
| `INTERNAL` | 内部错误 |

### 修复建议提示

错误信息自动附带修复建议（suggestion），帮助开发者快速定位问题：

```
类型不匹配: 无法将 String 转换为 Int
  提示: 使用 toInt() 方法进行转换
  --> script.nova:15
```

### 源码位置追踪

错误信息自动包含源码位置（文件名和行号），解释器路径通过 AST 的 `SourceLocation` 追踪，编译路径从 Java 堆栈帧中提取行号映射。

```
索引越界: 索引 5 超出范围 [0, 3)
  提示: 列表大小为 3，有效索引范围是 0..2
  --> main.nova:42
```

### Java 嵌入中的异常处理

在 Java 嵌入场景中，可以捕获 `NovaException` 获取结构化错误信息：

```java
try {
    nova.eval(script);
} catch (NovaException e) {
    NovaException.ErrorKind kind = e.getKind();       // 错误分类
    String suggestion = e.getSuggestion();             // 修复建议
    String sourceFile = e.getSourceFile();             // 源文件名
    int line = e.getSourceLineNumber();                // 源码行号
    String rawMessage = e.getRawMessage();             // 纯错误消息（不含建议/位置）
}
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

## Builder DSL / 接收者 Lambda（Java 嵌入 API）

### invokeWithReceiver — 以接收者调用 Nova Lambda

从 Java 侧以指定对象为作用域接收者调用 Nova callable。接收者的成员在 lambda 内可直接访问。

```java
Nova nova = new Nova();
nova.eval("val block = { put(\"key\", \"value\") }");
Object block = nova.get("block");

Map<String, String> map = new HashMap<>();
nova.invokeWithReceiver(block, map);
// map 现在包含 {"key": "value"}
```

### defineBuilderFunction — 定义 Builder 风格函数

注册一个函数：每次调用时创建接收者实例，以其为作用域执行 Nova lambda，最后返回处理结果。

```java
// 定义 builder 函数
nova.defineBuilderFunction("serverConfig", ServerConfig::new, config -> {
    config.validate();
    return config;
});

// 简化版：直接返回接收者
nova.defineBuilderFunction("config", Config::new);
```

Nova 脚本中使用：

```nova
val cfg = serverConfig {
    host = "0.0.0.0"
    port = 8080
}

val c = config {
    host = "localhost"
}
```

### 嵌套 Builder

Builder 函数支持嵌套，内层 lambda 的接收者自动切换：

```java
nova.defineBuilderFunction("server", ServerConfig::new);
nova.defineBuilderFunction("database", DatabaseConfig::new);
```

```nova
val srv = server {
    host = "0.0.0.0"
    val db = database {
        url = "jdbc:mysql://localhost/mydb"
        maxConnections = 10
    }
}
```
