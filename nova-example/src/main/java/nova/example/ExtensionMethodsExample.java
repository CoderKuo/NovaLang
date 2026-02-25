package nova.example;

import nova.runtime.*;
import nova.runtime.Nova;
import nova.runtime.interpreter.*;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 扩展方法示例
 *
 * 展示如何为现有类型注册扩展方法。
 */
public class ExtensionMethodsExample {

    public static void main(String[] args) {
        System.out.println("=== Nova 扩展方法示例 ===\n");

        Nova nova = new Nova();

        // 1. 为字符串类型添加扩展方法
        example1_StringExtensions(nova);

        // 2. 为数字类型添加扩展方法
        example2_NumberExtensions(nova);

        // 3. 为列表类型添加扩展方法
        example3_ListExtensions(nova);

        // 4. 实际应用：链式调用
        example4_ChainedCalls(nova);

        System.out.println("\n=== 示例完成 ===");
    }

    /**
     * 示例 1: 字符串扩展方法
     */
    private static void example1_StringExtensions(Nova nova) {
        System.out.println("--- 示例 1: 字符串扩展 ---");

        // 添加 reverse 方法
        nova.registerExtension(String.class, "reverse",
            new NovaNativeFunction("reverse", 0, (interp, args) -> {
                String str = args.get(0).asString(); // 第一个参数是接收者
                return NovaString.of(new StringBuilder(str).reverse().toString());
            })
        );

        // 添加 repeat 方法
        nova.registerExtension(String.class, "repeat",
            new NovaNativeFunction("repeat", 1, (interp, args) -> {
                String str = args.get(0).asString();
                int times = args.get(1).asInt();
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < times; i++) {
                    sb.append(str);
                }
                return NovaString.of(sb.toString());
            })
        );

        // 添加 words 方法（分割成单词）
        nova.registerExtension(String.class, "words",
            new NovaNativeFunction("words", 0, (interp, args) -> {
                String str = args.get(0).asString();
                String[] parts = str.trim().split("\\s+");
                NovaList list = new NovaList();
                for (String part : parts) {
                    list.add(NovaString.of(part));
                }
                return list;
            })
        );

        // 测试扩展方法
        Object reversed = nova.eval("\"Hello\".reverse()");
        System.out.println("\"Hello\".reverse() = " + reversed);

        Object repeated = nova.eval("\"ab\".repeat(3)");
        System.out.println("\"ab\".repeat(3) = " + repeated);

        Object words = nova.eval("\"Hello Nova World\".words()");
        System.out.println("\"Hello Nova World\".words() = " + words);

        System.out.println();
    }

    /**
     * 示例 2: 数字扩展方法
     */
    private static void example2_NumberExtensions(Nova nova) {
        System.out.println("--- 示例 2: 数字扩展 ---");

        // 为 Integer 添加 times 方法
        nova.registerExtension(Integer.class, "times",
            new NovaNativeFunction("times", 1, (interp, args) -> {
                int n = args.get(0).asInt();
                NovaValue func = args.get(1);
                if (func instanceof NovaCallable) {
                    NovaCallable callable = (NovaCallable) func;
                    for (int i = 0; i < n; i++) {
                        callable.call(interp, Collections.singletonList(new NovaInt(i)));
                    }
                }
                return NovaNull.UNIT;
            })
        );

        // 为 Integer 添加 isEven/isOdd 方法
        nova.registerExtension(Integer.class, "isEven",
            new NovaNativeFunction("isEven", 0, (interp, args) -> {
                int n = args.get(0).asInt();
                return NovaBoolean.of(n % 2 == 0);
            })
        );

        nova.registerExtension(Integer.class, "isOdd",
            new NovaNativeFunction("isOdd", 0, (interp, args) -> {
                int n = args.get(0).asInt();
                return NovaBoolean.of(n % 2 != 0);
            })
        );

        // 添加 rangeTo 方法（创建范围列表）
        nova.registerExtension(Integer.class, "rangeTo",
            new NovaNativeFunction("rangeTo", 1, (interp, args) -> {
                int start = args.get(0).asInt();
                int end = args.get(1).asInt();
                NovaList list = new NovaList();
                for (int i = start; i <= end; i++) {
                    list.add(new NovaInt(i));
                }
                return list;
            })
        );

        // 测试扩展方法
        System.out.print("3.times { i -> print(i) }: ");
        nova.eval("3.times { i -> print(i) }");
        System.out.println();

        Object isEven = nova.eval("4.isEven()");
        System.out.println("4.isEven() = " + isEven);

        Object isOdd = nova.eval("7.isOdd()");
        System.out.println("7.isOdd() = " + isOdd);

        Object range = nova.eval("1.rangeTo(5)");
        System.out.println("1.rangeTo(5) = " + range);

        System.out.println();
    }

    /**
     * 示例 3: 列表扩展方法
     */
    private static void example3_ListExtensions(Nova nova) {
        System.out.println("--- 示例 3: 列表扩展 ---");

        // 添加 sum 方法
        nova.registerExtension(java.util.List.class, "sum",
            new NovaNativeFunction("sum", 0, (interp, args) -> {
                NovaValue listVal = args.get(0);
                if (listVal instanceof NovaList) {
                    int sum = 0;
                    for (NovaValue v : (NovaList) listVal) {
                        sum += v.asInt();
                    }
                    return new NovaInt(sum);
                }
                return new NovaInt(0);
            })
        );

        // 添加 average 方法
        nova.registerExtension(java.util.List.class, "average",
            new NovaNativeFunction("average", 0, (interp, args) -> {
                NovaValue listVal = args.get(0);
                if (listVal instanceof NovaList) {
                    NovaList list = (NovaList) listVal;
                    if (list.size() == 0) return new NovaDouble(0.0);
                    double sum = 0;
                    for (NovaValue v : list) {
                        sum += v.asDouble();
                    }
                    return new NovaDouble(sum / list.size());
                }
                return new NovaDouble(0.0);
            })
        );

        // 添加 distinct 方法
        nova.registerExtension(java.util.List.class, "distinct",
            new NovaNativeFunction("distinct", 0, (interp, args) -> {
                NovaValue listVal = args.get(0);
                if (listVal instanceof NovaList) {
                    NovaList result = new NovaList();
                    Set<Object> seen = new HashSet<>();
                    for (NovaValue v : (NovaList) listVal) {
                        Object key = v.toJavaValue();
                        if (!seen.contains(key)) {
                            seen.add(key);
                            result.add(v);
                        }
                    }
                    return result;
                }
                return new NovaList();
            })
        );

        // 测试扩展方法
        Object sum = nova.eval("[1, 2, 3, 4, 5].sum()");
        System.out.println("[1, 2, 3, 4, 5].sum() = " + sum);

        Object avg = nova.eval("[10, 20, 30, 40, 50].average()");
        System.out.println("[10, 20, 30, 40, 50].average() = " + avg);

        Object distinct = nova.eval("[1, 2, 2, 3, 3, 3].distinct()");
        System.out.println("[1, 2, 2, 3, 3, 3].distinct() = " + distinct);

        System.out.println();
    }

    /**
     * 示例 4: 链式调用
     */
    private static void example4_ChainedCalls(Nova nova) {
        System.out.println("--- 示例 4: 链式调用 ---");

        // 综合使用扩展方法
        String script =
            "val text = \"hello world hello nova\"\n" +
            "val result = text.words().distinct()\n" +
            "result";

        Object result = nova.eval(script);
        System.out.println("链式调用结果: " + result);

        // 数字处理链
        String numScript =
            "val numbers = 1.rangeTo(10)\n" +
            "val filtered = numbers.filter { n -> n.isOdd() }\n" +
            "filtered.sum()";

        Object numResult = nova.eval(numScript);
        System.out.println("奇数之和 (1-10): " + numResult);

        System.out.println();
    }
}
