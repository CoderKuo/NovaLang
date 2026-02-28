# 函数注入与函数集 API

在 Java 中通过 `Nova` 便捷 API 向 Nova 脚本注入自定义函数，支持顶级函数注入、命名空间（函数集）分组注册和扩展方法注册。

## 顶级函数注入

### defineFunction — 便捷 Lambda 注入

将 Java Lambda 注册为 Nova 全局函数，参数和返回值自动在 Java 和 Nova 类型之间转换。支持 0 到 8 个参数。

```java
Nova nova = new Nova();

// 无参函数
nova.defineFunction("now", () -> System.currentTimeMillis());

// 单参函数
nova.defineFunction("double", (n) -> (int) n * 2);

// 双参函数
nova.defineFunction("add", (a, b) -> (int) a + (int) b);

// 三参函数
nova.defineFunction("clamp", (Object v, Object lo, Object hi) ->
        Math.max((int) lo, Math.min((int) hi, (int) v)));
```

Nova 脚本中直接调用：

```nova
println(now())         // 1708934400000
println(double(5))     // 10
println(add(10, 20))   // 30
println(clamp(15, 0, 10))  // 10
```

#### 4 参数及以上

由于 Java Lambda 类型推断的限制，4 参数以上需要显式指定函数接口类型：

```java
import nova.runtime.Function4;
import nova.runtime.Function5;
// ... Function6, Function7, Function8

nova.defineFunction("sum4",
    (Function4<Object, Object, Object, Object, Object>)
    (a, b, c, d) -> (int) a + (int) b + (int) c + (int) d);

nova.defineFunction("sum5",
    (Function5<Object, Object, Object, Object, Object, Object>)
    (a, b, c, d, e) -> (int) a + (int) b + (int) c + (int) d + (int) e);
```

#### 可变参数函数

使用 `defineFunctionVararg` 注册接受任意数量参数的函数：

```java
nova.defineFunctionVararg("sum", args -> {
    int total = 0;
    for (Object a : args) total += (int) a;
    return total;
});

nova.defineFunctionVararg("concat", args -> {
    StringBuilder sb = new StringBuilder();
    for (Object a : args) sb.append(a);
    return sb.toString();
});
```

```nova
println(sum())              // 0
println(sum(1, 2, 3))      // 6
println(concat("a", "b", "c"))  // abc
```

#### 返回 null

函数返回 `null` 时，Nova 侧接收到 `null`：

```java
nova.defineFunction("nothing", () -> null);
```

#### 接收 null 参数

Nova 传入 `null` 时，Java 侧收到 `null`：

```java
nova.defineFunction("orDefault", (a, b) -> a != null ? a : b);
```

```nova
println(orDefault(null, "fallback"))  // fallback
println(orDefault("value", "fallback"))  // value
```

#### 异常传播

Java 函数内抛出的异常会传播到 Nova 脚本层：

```java
nova.defineFunction("divide", (a, b) -> {
    if ((int) b == 0) throw new ArithmeticException("division by zero");
    return (int) a / (int) b;
});
```

```nova
try {
    divide(10, 0)
} catch (e) {
    println(e)  // division by zero
}
```

#### Fluent API

所有注册方法返回 `Nova` 实例，支持链式调用：

```java
Nova nova = new Nova()
    .defineFunction("inc", (n) -> (int) n + 1)
    .defineFunction("dec", (n) -> (int) n - 1)
    .defineFunctionVararg("max", args -> {
        int m = Integer.MIN_VALUE;
        for (Object a : args) m = Math.max(m, (int) a);
        return m;
    });
```

### alias — 函数/变量别名

为已注册的函数或变量创建一个或多个别名，所有别名指向同一个实现：

```java
nova.defineFunction("sum", (a, b) -> (int) a + (int) b);
nova.alias("sum", "求和", "add");

nova.defineVal("PI", 3.14159);
nova.alias("PI", "圆周率");
```

```nova
println(求和(10, 20))  // 30
println(add(10, 20))   // 30
println(圆周率)         // 3.14159
```

也可以在 `@NovaFunc` 注解中直接声明别名：

```java
public class MathFunctions {
    @NovaFunc(value = "sum", aliases = {"求和", "add"})
    public static int sum(int a, int b) { return a + b; }
}

nova.registerFunctions(MathFunctions.class);
// sum、求和、add 三个名称都可用
```

命名空间注册同样支持注解别名：

```java
nova.registerFunctions("math", MathFunctions.class);
// math.sum、math.求和、math.add 都可用
```

### aliasExtension — 扩展方法别名

为已注册的扩展方法创建别名：

```java
nova.registerExtension(String.class, "reverse",
    (s) -> new StringBuilder((String) s).reverse().toString());
nova.aliasExtension(String.class, "reverse", "反转", "rev");
```

```nova
println("hello".反转())  // olleh
println("hello".rev())   // olleh
```

也可以在 `@NovaExt` 注解中直接声明别名：

```java
public class StringExtensions {
    @NovaExt(value = "shout", aliases = {"大喊", "yell"})
    public static String shout(String self) {
        return self.toUpperCase() + "!";
    }
}

nova.registerExtensions(String.class, StringExtensions.class);
// "hello".shout()、"hello".大喊()、"hello".yell() 都可用
```

### hasFunction — 检查函数是否存在

```java
nova.defineFunction("greet", (name) -> "Hello, " + name);

nova.hasFunction("greet");     // true
nova.hasFunction("notExist");  // false
```

对非函数变量也返回 `false`：

```java
nova.set("x", 42);
nova.hasFunction("x");  // false
```

适用场景：Hook/模板模式中，执行前检查用户是否定义了特定回调函数。

```java
if (nova.hasFunction("onPlayerJoin")) {
    nova.call("onPlayerJoin", playerName);
}
```

### registerFunctions — @NovaFunc 注解批量注册

将 Java 类中所有 `@NovaFunc` 标注的静态方法一次性注册为顶级函数：

```java
import nova.runtime.interpreter.NovaFunc;

public class StringUtils {

    @NovaFunc("capitalize")
    public static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    @NovaFunc("repeat")
    public static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }
}
```

```java
nova.registerFunctions(StringUtils.class);
```

```nova
println(capitalize("hello"))  // Hello
println(repeat("ab", 3))     // ababab
```

`@NovaFunc` 方法要求：
- 必须是 `public static`
- 参数类型支持：`int`、`long`、`double`、`float`、`boolean`、`String`、`NovaValue`
- 返回类型支持：上述类型 + `void`（返回 null）
- 唯一参数为 `List<NovaValue>` 时，视为可变参数函数

高级用法：如果需要在函数内访问解释器，可以将 `Interpreter` 作为第一个参数，框架会自动注入（不计入参数数量）：

```java
@NovaFunc("evalCode")
public static Object evalCode(Interpreter interp, String code) {
    // interp 由框架自动注入，Nova 脚本调用时只需传 code 一个参数
    return interp.evalRepl(code);
}
```

```nova
evalCode("1 + 2")  // 只传一个参数，Interpreter 自动注入
```

---

## 函数集（命名空间）注册

将相关函数分组到一个命名空间下，在 Nova 脚本中通过 `namespace.func()` 调用，避免全局命名冲突。

### registerFunctions(namespace, Class) — 注解扫描到命名空间

与顶级注册相同的 `@NovaFunc` 类，指定命名空间即可：

```java
public class MathFunctions {
    @NovaFunc("sqrt")
    public static double sqrt(double x) { return Math.sqrt(x); }

    @NovaFunc("abs")
    public static int abs(int x) { return Math.abs(x); }
}

public class TrigFunctions {
    @NovaFunc("sin")
    public static double sin(double x) { return Math.sin(x); }

    @NovaFunc("cos")
    public static double cos(double x) { return Math.cos(x); }
}
```

```java
nova.registerFunctions("math", MathFunctions.class);
nova.registerFunctions("math", TrigFunctions.class);  // 增量合并到同一命名空间
```

```nova
println(math.sqrt(16.0))  // 4.0
println(math.abs(-5))     // 5
println(math.sin(0.0))    // 0.0
println(math.cos(0.0))    // 1.0
```

多次对同一命名空间调用 `registerFunctions` 会**合并**，已有函数不受影响。

### defineLibrary — Builder 模式注册

不需要写注解类，直接用 Lambda 编程注册：

```java
nova.defineLibrary("http", lib -> {
    lib.defineFunction("get", (url) -> httpGet((String) url));
    lib.defineFunction("post", (url, body) -> httpPost((String) url, (String) body));
    lib.defineVal("TIMEOUT", 30000);
    lib.defineVal("VERSION", "2.0");
});
```

```nova
val data = http.get("https://api.example.com/users")
http.post("https://api.example.com/users", "{\"name\": \"Alice\"}")
println(http.TIMEOUT)   // 30000
println(http.VERSION)   // 2.0
```

`LibraryBuilder` 支持与 `Nova` 相同的方法集：

| 方法 | 说明 |
|------|------|
| `defineVal(name, value)` | 注册不可变值 |
| `defineFunction(name, Function0..8)` | 注册 0-8 参数函数 |
| `defineFunctionVararg(name, func)` | 注册可变参数函数 |

Builder 同样支持增量合并：

```java
nova.defineLibrary("config", lib -> lib.defineVal("DEBUG", true));
nova.defineLibrary("config", lib -> lib.defineVal("VERSION", "1.0"));
// config 现在有 DEBUG 和 VERSION 两个成员
```

同名成员后注册的覆盖先注册的。

### registerObject — Java 对象直接暴露

将一个 Java 对象的所有公共方法直接暴露为命名空间，无需注解或 builder：

```java
public class DatabaseService {
    public String query(String sql) {
        return "result of: " + sql;
    }
    public void close() {
        System.out.println("connection closed");
    }
    public int getConnectionCount() {
        return 5;
    }
}

nova.registerObject("db", new DatabaseService());
```

```nova
val result = db.query("SELECT * FROM users")
println(result)              // result of: SELECT * FROM users
println(db.connectionCount)  // 5  (JavaBean getter: getConnectionCount())
db.close()                   // connection closed
```

特点：
- 自动暴露所有 `public` 方法
- 支持 JavaBean getter 属性访问（`getXxx()` → `.xxx`，`isXxx()` → `.xxx`）
- 参数和返回值自动在 Java 和 Nova 类型之间转换
- 无需额外注解或注册步骤

---

## 三种命名空间方式对比

| 特性 | `registerFunctions(ns, Class)` | `defineLibrary(name, builder)` | `registerObject(name, obj)` |
|------|------|------|------|
| 注册方式 | `@NovaFunc` 注解扫描 | Lambda builder | Java 对象直接暴露 |
| 适用场景 | 已有注解类，批量注册 | 灵活编程，按需组合 | 已有 Java 服务类 |
| 类型转换 | `@NovaFunc` 自动转换 | Lambda 手动处理 | Java 互操作自动转换 |
| 支持值/常量 | 不支持（仅函数） | 支持 `defineVal` | 支持 getter 属性 |
| 增量合并 | 支持 | 支持 | 不支持（整个对象） |
| 需要新建类 | 需要（静态方法类） | 不需要 | 需要（或已有） |

---

## 扩展方法注册

为已有的 Java 类型（如 `String`、`Integer`、`List` 等）注册扩展方法，在 Nova 脚本中以 `value.method()` 的形式调用。

### registerExtension — Lambda 便捷注册

将 Java Lambda 注册为指定类型的扩展方法。Lambda 的**第一个参数是 receiver**（即调用方法的对象本身），其余为方法参数。支持 0 到 7 个额外参数（receiver + 0~7）。

```java
Nova nova = new Nova();

// 0 额外参数（仅 receiver）
nova.registerExtension(String.class, "reverse",
    (s) -> new StringBuilder((String) s).reverse().toString());

// 1 额外参数
nova.registerExtension(String.class, "repeatN",
    (s, n) -> {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < (int) n; i++) sb.append((String) s);
        return sb.toString();
    });

// 2 额外参数
nova.registerExtension(String.class, "surround",
    (Object s, Object prefix, Object suffix) ->
        (String) prefix + (String) s + (String) suffix);
```

Nova 脚本中调用：

```nova
println("hello".reverse())            // olleh
println("ab".repeatN(3))              // ababab
println("hello".surround("[", "]"))   // [hello]
```

也可以为数值类型注册扩展方法：

```java
nova.registerExtension(Integer.class, "doubled", (n) -> (int) n * 2);
nova.registerExtension(Integer.class, "clampTo",
    (Object n, Object lo, Object hi) ->
        Math.max((int) lo, Math.min((int) hi, (int) n)));
```

```nova
println(10.doubled())        // 20
println(15.clampTo(0, 10))   // 10
```

#### 4 额外参数及以上

与 `defineFunction` 相同，4 参数以上需要显式指定函数接口类型：

```java
import nova.runtime.Function5;  // receiver + 4 extra args

nova.registerExtension(String.class, "wrap5",
    (Function5<Object, Object, Object, Object, Object, Object>)
    (s, a, b, c, d) -> (String) a + (String) b + (String) s + (String) c + (String) d);
```

#### 异常传播

扩展方法内抛出的异常同样传播到 Nova 脚本层：

```java
nova.registerExtension(String.class, "charAtSafe", (s, idx) -> {
    int i = (int) idx;
    if (i < 0 || i >= ((String) s).length())
        throw new IndexOutOfBoundsException("index " + i + " out of range");
    return String.valueOf(((String) s).charAt(i));
});
```

```nova
try {
    "hi".charAtSafe(10)
} catch (e) {
    println(e)  // index 10 out of range
}
```

#### Fluent API

所有 `registerExtension` 重载返回 `Nova` 实例，支持链式调用：

```java
Nova nova = new Nova()
    .registerExtension(String.class, "reverse",
        (s) -> new StringBuilder((String) s).reverse().toString())
    .registerExtension(String.class, "shout",
        (s) -> ((String) s).toUpperCase() + "!")
    .registerExtension(Integer.class, "isEven",
        (n) -> (int) n % 2 == 0);
```

### registerExtensions — @NovaExt 注解批量注册

将 Java 类中所有 `@NovaExt` 标注的静态方法一次性注册为扩展方法。**第一个参数约定为 receiver**，类型必须与注册的目标类型兼容。

```java
import nova.runtime.interpreter.NovaExt;

public class StringExtensions {

    @NovaExt("shout")
    public static String shout(String self) {
        return self.toUpperCase() + "!";
    }

    @NovaExt("truncate")
    public static String truncate(String self, int maxLen) {
        return self.length() <= maxLen ? self : self.substring(0, maxLen) + "...";
    }

    @NovaExt("words")
    public static String[] words(String self) {
        return self.split("\\s+");
    }
}
```

```java
nova.registerExtensions(String.class, StringExtensions.class);
```

```nova
println("hello".shout())              // HELLO!
println("hello world".truncate(5))    // hello...
```

多个类可以注册到同一目标类型，效果累加：

```java
public class MoreStringExtensions {
    @NovaExt("isPalindrome")
    public static boolean isPalindrome(String self) {
        String reversed = new StringBuilder(self).reverse().toString();
        return self.equals(reversed);
    }
}

nova.registerExtensions(String.class, StringExtensions.class);
nova.registerExtensions(String.class, MoreStringExtensions.class);
```

```nova
println("hello".shout())          // HELLO!
println("racecar".isPalindrome()) // true
```

`@NovaExt` 方法要求：
- 必须是 `public static`
- 第一个参数为 receiver（调用方法的对象本身），类型必须与目标类型兼容（本身或其父类/接口）
- 其余参数类型支持：`int`、`long`、`double`、`float`、`boolean`、`String`、`NovaValue`
- 返回类型支持：上述类型 + `void`（返回 null）

高级用法：如果需要在扩展方法内访问解释器，可以将 `Interpreter` 作为第一个参数，框架会自动注入，此时第二个参数才是 receiver：

```java
@NovaExt("evalInside")
public static Object evalInside(Interpreter interp, String self) {
    // interp 由框架自动注入，self 才是 receiver
    return interp.evalRepl(self);
}
```

### 两种扩展方法注册方式对比

| 特性 | `registerExtension(type, name, lambda)` | `registerExtensions(type, Class)` |
|------|------|------|
| 注册方式 | Lambda 逐个注册 | `@NovaExt` 注解批量扫描 |
| 适用场景 | 少量扩展，灵活编程 | 大量扩展，统一管理 |
| 类型转换 | Lambda 手动 cast | `@NovaExt` 自动转换 |
| 需要新建类 | 不需要 | 需要（静态方法类） |
| receiver 传入方式 | Lambda 第一个参数 | 方法第一个参数 |

---

## 综合示例

```java
Nova nova = new Nova();

// 1. 顶级便捷函数
nova.defineFunction("println2", (msg) -> {
    System.out.println("[Nova] " + msg);
    return null;
});

// 2. @NovaFunc 注解扫描到命名空间
nova.registerFunctions("str", StringUtils.class);

// 3. Builder 模式创建配置命名空间
nova.defineLibrary("config", lib -> {
    lib.defineVal("APP_NAME", "MyApp");
    lib.defineVal("DEBUG", false);
    lib.defineFunction("get", (key) -> System.getProperty((String) key));
});

// 4. Java 对象直接暴露
nova.registerObject("db", new DatabaseService());

// 5. Lambda 扩展方法
nova.registerExtension(String.class, "reverse",
    (s) -> new StringBuilder((String) s).reverse().toString());

// 6. @NovaExt 批量扩展方法
nova.registerExtensions(String.class, StringExtensions.class);

// 执行脚本
nova.eval("""
    println2(config.APP_NAME)
    println2(str.capitalize("hello"))
    val result = db.query("SELECT 1")
    println2(result)
    println2("hello".reverse())
    println2("world".shout())
""");
```

输出：

```
[Nova] MyApp
[Nova] Hello
[Nova] result of: SELECT 1
[Nova] olleh
[Nova] WORLD!
```

---

## 预编译执行

当同一段 Nova 代码需要反复执行时（如配置文件中的计算公式），应使用预编译避免重复解析。Nova 提供两种预编译模式。

### compile — 解释器预编译

`compile()` 在构造时完成词法分析和语法解析，将源代码转换为 AST 并缓存。每次 `run()` 跳过解析阶段，直接从 AST 进入 MIR 管线执行。

```java
Nova nova = new Nova();
nova.defineFunction("max", (a, b) ->
    Math.max(((Number) a).doubleValue(), ((Number) b).doubleValue()));

// 预编译 — 此时完成解析，缓存 AST
CompiledNova formula = nova.compile("baseDamage * (1 + level * 0.1)", "damage");

// 多次执行 — 只走 MIR 管线，不再重新解析
nova.set("baseDamage", 100).set("level", 5);
formula.run();  // 150.0

nova.set("baseDamage", 80).set("level", 10);
formula.run();  // 160.0
```

与 `Nova` 实例**共享环境**：通过 `nova.set()` 修改的变量在 `formula.run()` 中立即生效，通过 `nova.defineFunction()` 注入的函数也可以在公式中直接调用。

#### 典型场景：配置文件动态公式

```java
// 插件启动时，从配置加载并预编译所有公式
Map<String, CompiledNova> formulas = new HashMap<>();
Nova nova = new Nova();

for (String key : config.getKeys()) {
    String expr = config.getString(key);
    formulas.put(key, nova.compile(expr, key));
}

// 运行时按需执行
public double evaluate(String key, String varName, double value) {
    nova.set(varName, value);
    return ((Number) formulas.get(key).run()).doubleValue();
}
```

```yaml
# config.yml
formulas:
  damage: "baseDamage * (1 + level * 0.1) * critMultiplier"
  heal: "baseHeal + max(level * 2, 10)"
  exp-needed: "100 * level * level + 50 * level"
```

几百个简单表达式预编译后内存占用很小（只有 AST 节点），执行速度也足够快。

### compileToBytecode — 字节码编译

`compileToBytecode()` 将 Nova 代码编译为真正的 JVM 字节码，生成并加载 Java 类。执行时走 JVM 原生调用路径，速度与手写 Java 相当。

```java
Nova nova = new Nova();
nova.set("rate", 0.15);

CompiledNova compiled = nova.compileToBytecode("price * quantity * (1 + rate)");
compiled.set("price", 100).set("quantity", 5);
compiled.run();  // 575.0
```

与 `compile()` 不同，字节码模式**独立运行**，不依赖 Nova 解释器环境。变量通过 `compiled.set()` 直接绑定到编译后的字节码。

### 两种预编译模式对比

| | `compile()` | `compileToBytecode()` |
|---|---|---|
| 构造开销 | 低（词法 + 语法解析） | 高（解析 + IR 生成 + 字节码生成 + 类加载） |
| 单次内存 | 小（AST 节点） | 大（JVM 类 + Metaspace） |
| 执行速度 | 解释执行（MIR 管线） | JVM 原生速度 |
| 环境模型 | 与 Nova 实例**共享**环境 | **独立**环境，通过 `set()` 绑定 |
| 适用场景 | 配置公式、模板表达式、规则引擎 | 高频热点（每秒数万次调用） |

**选型建议**：

- 配置文件中的计算公式、条件表达式 → **`compile()`**
- 只执行一次的临时脚本 → 直接 **`eval()`**
- 游戏主循环中每 tick 执行的核心逻辑 → **`compileToBytecode()`**
