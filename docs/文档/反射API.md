# 反射 API

NovaLang 提供运行时反射 API，可在程序运行期间查询和操作类的元信息，包括字段、方法、注解、可见性等。

---

## classOf() 函数

`classOf()` 是反射 API 的入口函数，接收类或实例，返回 `ClassInfo` 对象：

```nova
class Person(val name: String, var age: Int)
val p = Person("Alice", 30)

// 通过类获取
val info1 = classOf(Person)

// 通过实例获取
val info2 = classOf(p)

// 两者等价
println(info1.name)  // "Person"
println(info2.name)  // "Person"
```

---

## ClassInfo

`ClassInfo` 包含类的完整元信息。

### 属性

| 属性 | 类型 | 说明 |
|------|------|------|
| `name` | `String` | 类名 |
| `superclass` | `String?` | 父类名，无父类时为 `null` |
| `interfaces` | `List<String>` | 实现的接口名列表 |
| `fields` | `List<FieldInfo>` | 所有字段信息列表 |
| `methods` | `List<MethodInfo>` | 所有方法信息列表 |
| `annotations` | `List<AnnotationInfo>` | 类上的注解列表 |

### 方法

| 方法 | 返回值 | 说明 |
|------|--------|------|
| `field(name)` | `FieldInfo` | 按名称查找字段 |
| `method(name)` | `MethodInfo` | 按名称查找方法 |

### 示例

```nova
open class Animal { fun speak() = "..." }
interface Drawable { fun draw(): String }
class Dog : Animal, Drawable {
    fun speak() = "Woof"
    fun draw() = "🐕"
}

val info = classOf(Dog)
println(info.name)                  // "Dog"
println(info.superclass)            // "Animal"
println(info.interfaces)            // ["Drawable"]
println(info.methods.size())        // 2
```

---

## FieldInfo

表示类的一个字段（构造器参数）。

### 属性

| 属性 | 类型 | 说明 |
|------|------|------|
| `name` | `String` | 字段名 |
| `type` | `String?` | 类型名（如 `"String"`、`"Int"`），无类型标注时为 `null` |
| `visibility` | `String` | 可见性：`"public"`、`"private"`、`"protected"`、`"internal"` |
| `mutable` | `Boolean` | 是否可变（`var` 为 `true`，`val` 为 `false`） |

### 方法

| 方法 | 说明 |
|------|------|
| `get(obj)` | 读取指定对象上该字段的值 |
| `set(obj, value)` | 修改指定对象上该字段的值（仅限 `var` 字段） |

### 示例

```nova
class User(val name: String, var age: Int, private val secret: String)

val info = classOf(User)

// 遍历字段
for (f in info.fields) {
    println("${f.name}: ${f.type}, mutable=${f.mutable}, visibility=${f.visibility}")
}
// name: String, mutable=false, visibility=public
// age: Int, mutable=true, visibility=public
// secret: String, mutable=false, visibility=private

// 读写字段值
val u = User("Alice", 25, "abc")
val nameField = info.field("name")
println(nameField.get(u))  // "Alice"

val ageField = info.field("age")
ageField.set(u, 26)
println(u.age)  // 26
```

---

## MethodInfo

表示类的一个方法。

### 属性

| 属性 | 类型 | 说明 |
|------|------|------|
| `name` | `String` | 方法名 |
| `visibility` | `String` | 可见性 |
| `params` | `List<ParamInfo>` | 参数信息列表 |

### 方法

| 方法 | 说明 |
|------|------|
| `call(obj, args...)` | 在指定对象上调用该方法，传入参数 |

### 示例

```nova
class Calculator {
    fun add(a: Int, b: Int) = a + b
    fun greet(name: String) = "Hello, $name"
}

val info = classOf(Calculator)
val calc = Calculator()

// 按名称获取方法并调用
val addMethod = info.method("add")
println(addMethod.call(calc, 3, 4))  // 7

// 查看参数信息
for (p in addMethod.params) {
    println("${p.name}: ${p.type}")
}
// a: Int
// b: Int

// 无参方法调用
class Counter(var value: Int) {
    fun increment() { value = value + 1 }
}
val c = Counter(0)
classOf(c).method("increment").call(c)
classOf(c).method("increment").call(c)
println(c.value)  // 2
```

---

## ParamInfo

表示方法的一个参数。

| 属性 | 类型 | 说明 |
|------|------|------|
| `name` | `String` | 参数名 |
| `type` | `String?` | 类型名，无类型标注时为 `null` |
| `hasDefault` | `Boolean` | 是否有默认值 |

---

## AnnotationInfo

表示一个注解实例。

| 属性 | 类型 | 说明 |
|------|------|------|
| `name` | `String` | 注解名 |
| `args` | `Map` | 注解参数 |

```nova
@data @builder class Point(val x: Int, val y: Int)

val anns = classOf(Point).annotations
for (a in anns) {
    println("${a.name}: ${a.args}")
}
// data: {}
// builder: {}
```

---

## 常见用法

### 通用序列化

```nova
fun toJson(obj): String {
    val info = classOf(obj)
    var result = "{"
    val fields = info.fields
    for (i in 0..<fields.size()) {
        val f = fields[i]
        val value = f.get(obj)
        if (i > 0) result = result + ", "
        if (typeof(value) == "String") {
            result = result + "\"" + f.name + "\": \"" + value + "\""
        } else {
            result = result + "\"" + f.name + "\": " + value
        }
    }
    return result + "}"
}

class User(val name: String, val age: Int)
println(toJson(User("Alice", 25)))
// {"name": "Alice", "age": 25}
```

### 通用对象复制

```nova
class Point(var x: Int, var y: Int)
val src = Point(10, 20)
val dst = Point(0, 0)

for (f in classOf(src).fields) {
    f.set(dst, f.get(src))
}
println(dst.x)  // 10
println(dst.y)  // 20
```

### 通用字段比较

```nova
fun reflectEquals(a, b): Boolean {
    val infoA = classOf(a)
    val infoB = classOf(b)
    if (infoA.name != infoB.name) return false
    for (f in infoA.fields) {
        if (f.get(a) != f.get(b)) return false
    }
    return true
}

class Vec(val x: Int, val y: Int)
println(reflectEquals(Vec(1, 2), Vec(1, 2)))  // true
println(reflectEquals(Vec(1, 2), Vec(1, 3)))  // false
```

### 对象转 Map

```nova
fun toMap(obj): Map {
    val info = classOf(obj)
    val map = mapOf()
    for (f in info.fields) {
        map[f.name] = f.get(obj)
    }
    return map
}

class Pos(val x: Int, val y: Int, val z: Int)
val m = toMap(Pos(1, 2, 3))
println(m)  // {x: 1, y: 2, z: 3}
```

### 过滤 public 字段

```nova
class Entity(val id: Int, private val secret: String, val name: String)

val pubFields = classOf(Entity).fields.filter { f -> f.visibility == "public" }
for (f in pubFields) {
    println(f.name)
}
// id
// name
```

### 类描述生成

```nova
fun describe(cls) {
    val info = classOf(cls)
    var desc = "class " + info.name + "("
    val fields = info.fields
    for (i in 0..<fields.size()) {
        if (i > 0) desc = desc + ", "
        val f = fields[i]
        desc = desc + (if (f.mutable) "var" else "val") + " " + f.name
        if (f.type != null) desc = desc + ": " + f.type
    }
    return desc + ")"
}

class Person(val name: String, var age: Int)
println(describe(Person))
// class Person(val name: String, var age: Int)
```

---

## 注意事项

- `classOf()` 接受类引用或实例，两者返回相同类的 `ClassInfo`
- `FieldInfo.set()` 只能修改 `var` 字段，对 `val` 字段调用不会生效
- 反射可以绕过 `private`/`protected` 可见性限制访问字段（通过 `field.get()`/`field.set()`）
- `fields` 只包含主构造器中声明的参数字段
- `methods` 只包含类体中显式定义的方法
