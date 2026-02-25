package nova.example;

import nova.runtime.Nova;

/**
 * 基础用法示例
 *
 * 展示如何在 Java 程序中嵌入 Nova 执行脚本。
 */
public class BasicUsageExample {

    public static void main(String[] args) {
        System.out.println("=== Nova 基础用法示例 ===\n");

        // 1. 创建 Nova 实例
        Nova nova = new Nova();

        // 2. 执行简单表达式
        example1_SimpleExpressions(nova);

        // 3. 执行多行脚本
        example2_MultiLineScript(nova);

        // 4. 变量和函数定义
        example3_VariablesAndFunctions(nova);

        // 5. 获取返回值
        example4_ReturnValues(nova);

        // 6. 类和对象
        example5_ClassesAndObjects(nova);

        System.out.println("\n=== 示例完成 ===");
    }

    /**
     * 示例 1: 执行简单表达式
     */
    private static void example1_SimpleExpressions(Nova nova) {
        System.out.println("--- 示例 1: 简单表达式 ---");

        // 算术表达式
        Object result = nova.eval("1 + 2 * 3");
        System.out.println("1 + 2 * 3 = " + result);

        // 字符串操作
        result = nova.eval("\"Hello, \" + \"Nova!\"");
        System.out.println("字符串拼接: " + result);

        // 布尔表达式
        result = nova.eval("10 > 5 && 3 < 7");
        System.out.println("10 > 5 && 3 < 7 = " + result);

        System.out.println();
    }

    /**
     * 示例 2: 执行多行脚本
     */
    private static void example2_MultiLineScript(Nova nova) {
        System.out.println("--- 示例 2: 多行脚本 ---");

        String script =
            "val numbers = [1, 2, 3, 4, 5]\n" +
            "var sum = 0\n" +
            "for (n in numbers) {\n" +
            "    sum = sum + n\n" +
            "}\n" +
            "sum";

        Object result = nova.eval(script);
        System.out.println("数组求和结果: " + result);
        System.out.println();
    }

    /**
     * 示例 3: 变量和函数定义
     */
    private static void example3_VariablesAndFunctions(Nova nova) {
        System.out.println("--- 示例 3: 变量和函数 ---");

        // 定义变量（PI 是内置常量，直接使用）
        nova.eval("var radius = 5.0");

        // 定义函数（使用内置的 PI 常量）
        nova.eval("fun circleArea(r) { return PI * r * r }");

        // 调用函数
        Object area = nova.eval("circleArea(radius)");
        System.out.println("圆面积 (r=5): " + area);

        // 修改变量并重新计算
        nova.eval("radius = 10.0");
        area = nova.eval("circleArea(radius)");
        System.out.println("圆面积 (r=10): " + area);

        System.out.println();
    }

    /**
     * 示例 4: 获取不同类型的返回值
     */
    private static void example4_ReturnValues(Nova nova) {
        System.out.println("--- 示例 4: 返回值类型 ---");

        // 整数
        Object intVal = nova.eval("42");
        System.out.println("整数: " + intVal + " (类型: " + intVal.getClass().getSimpleName() + ")");

        // 浮点数
        Object floatVal = nova.eval("3.14");
        System.out.println("浮点数: " + floatVal + " (类型: " + floatVal.getClass().getSimpleName() + ")");

        // 字符串
        Object strVal = nova.eval("\"Nova\"");
        System.out.println("字符串: " + strVal + " (类型: " + strVal.getClass().getSimpleName() + ")");

        // 布尔值
        Object boolVal = nova.eval("true");
        System.out.println("布尔值: " + boolVal + " (类型: " + boolVal.getClass().getSimpleName() + ")");

        // 列表
        Object listVal = nova.eval("[1, 2, 3]");
        System.out.println("列表: " + listVal + " (类型: " + listVal.getClass().getSimpleName() + ")");

        // Map (使用 mapOf 函数创建)
        Object mapVal = nova.eval("mapOf(\"a\", 1, \"b\", 2)");
        System.out.println("Map: " + mapVal + " (类型: " + mapVal.getClass().getSimpleName() + ")");

        System.out.println();
    }

    /**
     * 示例 5: 类和对象
     */
    private static void example5_ClassesAndObjects(Nova nova) {
        System.out.println("--- 示例 5: 类和对象 ---");

        // 定义类
        nova.eval(
            "class Person(val name: String, var age: Int) {\n" +
            "    fun greet() { return \"Hello, I'm \" + name }\n" +
            "    fun birthday() { age = age + 1 }\n" +
            "}"
        );

        // 创建实例
        nova.eval("val alice = Person(\"Alice\", 25)");

        // 访问属性和方法
        Object name = nova.eval("alice.name");
        Object greeting = nova.eval("alice.greet()");
        System.out.println("姓名: " + name);
        System.out.println("问候: " + greeting);

        // 修改属性
        nova.eval("alice.birthday()");
        Object age = nova.eval("alice.age");
        System.out.println("过生日后年龄: " + age);

        System.out.println();
    }
}
