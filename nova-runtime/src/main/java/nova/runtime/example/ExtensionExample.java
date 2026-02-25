package nova.runtime.example;

import nova.lang.Unit;
import nova.runtime.*;

import java.util.Arrays;
import java.util.List;

/**
 * NovaRuntime 扩展 API 使用示例
 */
public class ExtensionExample {

    public static void main(String[] args) throws Exception {
        NovaRuntime runtime = NovaRuntime.create();

        // ============ 1. 注册全局函数 ============

        // 无参数函数
        runtime.registerFunction("now", Long.class,
                () -> System.currentTimeMillis());

        // 单参数函数
        runtime.registerFunction("print", String.class, Unit.class,
                (msg) -> {
                    System.out.println(msg);
                    return Unit.INSTANCE;
                });

        // 双参数函数
        runtime.registerFunction("add", Integer.class, Integer.class, Integer.class,
                (a, b) -> a + b);

        runtime.registerFunction("max", Integer.class, Integer.class, Integer.class,
                (a, b) -> Math.max(a, b));

        // ============ 2. 注册扩展方法 ============

        // String 扩展
        runtime.registerExtension(String.class, "shout", String.class,
                (s) -> s.toUpperCase() + "!");

        runtime.registerExtension(String.class, "reverse", String.class,
                (s) -> new StringBuilder(s).reverse().toString());

        runtime.registerExtension(String.class, "repeat", Integer.class, String.class,
                (s, n) -> {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < n; i++) {
                        sb.append(s);
                    }
                    return sb.toString();
                });

        runtime.registerExtension(String.class, "isBlank", Boolean.class,
                (s) -> s.trim().isEmpty());

        // Integer 扩展
        runtime.registerExtension(Integer.class, "isEven", Boolean.class,
                (n) -> n % 2 == 0);

        runtime.registerExtension(Integer.class, "isOdd", Boolean.class,
                (n) -> n % 2 != 0);

        runtime.registerExtension(Integer.class, "square", Integer.class,
                (n) -> n * n);

        // List 扩展
        runtime.registerExtension(List.class, "head", Object.class,
                (list) -> list.isEmpty() ? null : list.get(0));

        runtime.registerExtension(List.class, "tail", List.class,
                (list) -> list.size() <= 1 ?
                        java.util.Collections.emptyList() :
                        list.subList(1, list.size()));

        runtime.registerExtension(List.class, "second", Object.class,
                (list) -> list.size() > 1 ? list.get(1) : null);

        // ============ 3. 使用链式 API ============

        runtime.extensions()
                .forType(String.class)
                    .add("words", List.class,
                            (s) -> Arrays.asList(s.split("\\s+")))
                    .add("capitalize", String.class,
                            (s) -> s.isEmpty() ? s :
                                    Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase())
                .forType(Integer.class)
                    .add("abs", Integer.class,
                            (n) -> Math.abs(n))
                    .add("clamp", Integer.class, Integer.class, Integer.class,
                            (n, min, max) -> Math.min(Math.max(n, min), max))
                .register();

        // ============ 4. 设置全局变量 ============

        runtime.setGlobal("PI", Math.PI);
        runtime.setGlobal("E", Math.E);
        runtime.setGlobal("DEBUG", true);

        // ============ 5. 测试调用 ============

        System.out.println("=== 测试全局函数 ===");
        System.out.println("add(3, 5) = " + runtime.invokeFunction("add", 3, 5));
        System.out.println("max(10, 7) = " + runtime.invokeFunction("max", 10, 7));
        System.out.println("now() = " + runtime.invokeFunction("now"));

        System.out.println("\n=== 测试字符串扩展 ===");
        System.out.println("\"hello\".shout() = " + runtime.invokeExtension("hello", "shout"));
        System.out.println("\"hello\".reverse() = " + runtime.invokeExtension("hello", "reverse"));
        System.out.println("\"ab\".repeat(3) = " + runtime.invokeExtension("ab", "repeat", 3));
        System.out.println("\"  \".isBlank() = " + runtime.invokeExtension("  ", "isBlank"));
        System.out.println("\"hello world\".words() = " + runtime.invokeExtension("hello world", "words"));
        System.out.println("\"hELLO\".capitalize() = " + runtime.invokeExtension("hELLO", "capitalize"));

        System.out.println("\n=== 测试整数扩展 ===");
        System.out.println("4.isEven() = " + runtime.invokeExtension(4, "isEven"));
        System.out.println("5.isOdd() = " + runtime.invokeExtension(5, "isOdd"));
        System.out.println("7.square() = " + runtime.invokeExtension(7, "square"));
        System.out.println("(-5).abs() = " + runtime.invokeExtension(-5, "abs"));
        System.out.println("15.clamp(0, 10) = " + runtime.invokeExtension(15, "clamp", 0, 10));

        System.out.println("\n=== 测试列表扩展 ===");
        List<String> list = Arrays.asList("a", "b", "c", "d");
        System.out.println("list = " + list);
        System.out.println("list.head() = " + runtime.invokeExtension(list, "head"));
        System.out.println("list.second() = " + runtime.invokeExtension(list, "second"));
        System.out.println("list.tail() = " + runtime.invokeExtension(list, "tail"));

        System.out.println("\n=== 测试全局变量 ===");
        System.out.println("PI = " + runtime.getGlobal("PI"));
        System.out.println("DEBUG = " + runtime.getGlobal("DEBUG"));

        System.out.println("\n=== 测试完成 ===");
    }
}
