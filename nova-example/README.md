# Nova Example - 嵌入式使用示例

本模块展示如何在 Java 程序中嵌入 Nova 解释器。

## 示例列表

### 1. BasicUsageExample - 基础用法
展示解释器的基本使用方法：
- 执行简单表达式
- 执行多行脚本
- 定义变量和函数
- 获取返回值
- 使用类和对象

```bash
./gradlew :nova-example:runBasic
```

### 2. CustomFunctionsExample - 自定义函数
展示如何注册自定义 Java 函数供 Nova 调用：
- 简单函数注册
- 带参数的函数
- 命名参数支持
- 可变参数函数

```bash
./gradlew :nova-example:runCustomFunctions
```

### 3. JavaInteropExample - Java 互操作
展示 Nova 与 Java 的互操作能力：
- 导入 Java 类
- 调用静态方法
- 创建 Java 对象
- 使用 Java 集合

```bash
./gradlew :nova-example:runJavaInterop
```

### 4. ExtensionMethodsExample - 扩展方法
展示如何为现有类型添加扩展方法：
- 字符串扩展
- 数字扩展
- 列表扩展
- 链式调用

```bash
./gradlew :nova-example:runExtensionMethods
```

## 运行所有示例

```bash
./gradlew :nova-example:runAllExamples
```

## 快速开始

### Maven 依赖

```xml
<dependency>
    <groupId>com.novalang</groupId>
    <artifactId>nova-runtime</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Gradle 依赖

```groovy
implementation 'com.novalang:nova-runtime:1.0.0'
```

### 最简示例

```java
import nova.runtime.interpreter.Interpreter;
import nova.runtime.interpreter.NovaValue;

public class QuickStart {
    public static void main(String[] args) {
        // 1. 创建解释器
        Interpreter interpreter = new Interpreter();

        // 2. 执行 Nova 代码
        NovaValue result = interpreter.evalRepl("1 + 2 * 3");

        // 3. 获取结果
        System.out.println("Result: " + result.asInt());  // 输出: Result: 7
    }
}
```

### 注册自定义函数

```java
// 方式 1: 使用工厂方法（推荐）
interpreter.getGlobals().defineVal("myFunc",
    NovaNativeFunction.create("myFunc", arg -> {
        String input = arg.asString();
        return new NovaString("Processed: " + input);
    })
);

// 方式 2: 使用 lambda
interpreter.getGlobals().defineVal("add",
    new NovaNativeFunction("add", 2, (interp, args) -> {
        int a = args.get(0).asInt();
        int b = args.get(1).asInt();
        return new NovaInt(a + b);
    })
);

// 方式 3: 可变参数
interpreter.getGlobals().defineVal("sum",
    NovaNativeFunction.createVararg("sum", (interp, args) -> {
        int total = 0;
        for (NovaValue arg : args) {
            total += arg.asInt();
        }
        return new NovaInt(total);
    })
);

// 在 Nova 中调用
interpreter.evalRepl("myFunc(\"hello\")");  // 返回 "Processed: hello"
interpreter.evalRepl("add(1, 2)");          // 返回 3
interpreter.evalRepl("sum(1, 2, 3, 4)");    // 返回 10
```

### 注册扩展方法

```java
interpreter.registerExtension(String.class, "shout",
    new NovaNativeFunction("shout", 0, (interp, args) -> {
        return new NovaString(args.get(0).asString().toUpperCase() + "!");
    })
);

// 在 Nova 中使用
interpreter.evalRepl("\"hello\".shout()");  // 返回 "HELLO!"
```
