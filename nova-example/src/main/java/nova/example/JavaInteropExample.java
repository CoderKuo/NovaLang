package nova.example;

import nova.runtime.Nova;

/**
 * Java 互操作示例
 *
 * 展示 Nova 如何与 Java 类和对象进行交互。
 */
public class JavaInteropExample {

    public static void main(String[] args) {
        System.out.println("=== Nova Java 互操作示例 ===\n");

        Nova nova = new Nova();

        // 1. 导入和使用 Java 类
        example1_ImportJavaClass(nova);

        // 2. 调用静态方法
        example2_StaticMethods(nova);

        // 3. 创建 Java 对象
        example3_CreateObjects(nova);

        // 4. 使用 Java 集合
        example4_JavaCollections(nova);

        // 5. 使用 Java 字符串方法
        example5_StringMethods(nova);

        System.out.println("\n=== 示例完成 ===");
    }

    /**
     * 示例 1: 导入和使用 Java 类
     */
    private static void example1_ImportJavaClass(Nova nova) {
        System.out.println("--- 示例 1: 导入 Java 类 ---");

        // 使用 javaClass 获取 Java 类引用
        nova.eval("val System = javaClass(\"java.lang.System\")");
        nova.eval("val Math = javaClass(\"java.lang.Math\")");

        // 访问静态字段
        Object pi = nova.eval("Math.PI");
        System.out.println("Math.PI = " + pi);

        Object e = nova.eval("Math.E");
        System.out.println("Math.E = " + e);

        System.out.println();
    }

    /**
     * 示例 2: 调用 Java 静态方法
     */
    private static void example2_StaticMethods(Nova nova) {
        System.out.println("--- 示例 2: 静态方法调用 ---");

        // Math 类已在 example1 中定义，直接使用
        Object sqrt = nova.eval("Math.sqrt(16.0)");
        System.out.println("Math.sqrt(16) = " + sqrt);

        Object abs = nova.eval("Math.abs(-42)");
        System.out.println("Math.abs(-42) = " + abs);

        Object max = nova.eval("Math.max(10, 20)");
        System.out.println("Math.max(10, 20) = " + max);

        // Integer 类的方法
        nova.eval("val Integer = javaClass(\"java.lang.Integer\")");

        Object parsed = nova.eval("Integer.parseInt(\"123\")");
        System.out.println("Integer.parseInt(\"123\") = " + parsed);

        Object hex = nova.eval("Integer.toHexString(255)");
        System.out.println("Integer.toHexString(255) = " + hex);

        System.out.println();
    }

    /**
     * 示例 3: 创建 Java 对象
     */
    private static void example3_CreateObjects(Nova nova) {
        System.out.println("--- 示例 3: 创建 Java 对象 ---");

        // 创建 StringBuilder
        nova.eval("val StringBuilder = javaClass(\"java.lang.StringBuilder\")");
        nova.eval("val sb = StringBuilder()");
        nova.eval("sb.append(\"Hello\")");
        nova.eval("sb.append(\" \")");
        nova.eval("sb.append(\"World\")");

        Object result = nova.eval("sb.toString()");
        System.out.println("StringBuilder 结果: " + result);

        // 创建 Date (旧 API 示例)
        nova.eval("val Date = javaClass(\"java.util.Date\")");
        nova.eval("val now = Date()");

        Object dateStr = nova.eval("now.toString()");
        System.out.println("当前日期: " + dateStr);

        System.out.println();
    }

    /**
     * 示例 4: 使用 Java 集合
     */
    private static void example4_JavaCollections(Nova nova) {
        System.out.println("--- 示例 4: Java 集合 ---");

        // ArrayList
        nova.eval("val ArrayList = javaClass(\"java.util.ArrayList\")");
        nova.eval("val list = ArrayList()");
        nova.eval("list.add(\"apple\")");
        nova.eval("list.add(\"banana\")");
        nova.eval("list.add(\"cherry\")");

        Object size = nova.eval("list.size()");
        System.out.println("ArrayList 大小: " + size);

        Object first = nova.eval("list.get(0)");
        System.out.println("第一个元素: " + first);

        // HashMap
        nova.eval("val HashMap = javaClass(\"java.util.HashMap\")");
        nova.eval("val map = HashMap()");
        nova.eval("map.put(\"name\", \"Nova\")");
        nova.eval("map.put(\"version\", \"1.0\")");

        Object name = nova.eval("map.get(\"name\")");
        System.out.println("Map[name] = " + name);

        Object containsKey = nova.eval("map.containsKey(\"version\")");
        System.out.println("Map 包含 'version': " + containsKey);

        System.out.println();
    }

    /**
     * 示例 5: 使用 Java 字符串方法
     */
    private static void example5_StringMethods(Nova nova) {
        System.out.println("--- 示例 5: 字符串方法 ---");

        // Nova 字符串可以直接调用 Java String 的方法
        nova.eval("val text = \"  Hello, Nova!  \"");

        Object upper = nova.eval("text.toUpperCase()");
        System.out.println("toUpperCase: \"" + upper + "\"");

        Object trimmed = nova.eval("text.trim()");
        System.out.println("trim: \"" + trimmed + "\"");

        Object replaced = nova.eval("text.replace(\"Nova\", \"World\")");
        System.out.println("replace: \"" + replaced + "\"");

        Object startsWith = nova.eval("\"Hello\".startsWith(\"He\")");
        System.out.println("startsWith(\"He\"): " + startsWith);

        // 字符串分割 - split 返回 NovaList
        nova.eval("val parts = \"a,b,c,d\".split(\",\")");
        // NovaList 使用 size() 获取长度
        Object length = nova.eval("parts.size()");
        System.out.println("split 结果长度: " + length);

        System.out.println();
    }
}
