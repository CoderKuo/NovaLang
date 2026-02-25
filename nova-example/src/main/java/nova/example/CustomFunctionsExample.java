package nova.example;

import nova.runtime.*;
import nova.runtime.Nova;
import nova.runtime.interpreter.*;

/**
 * 自定义函数示例
 *
 * 展示如何向 Nova 注册自定义的 Java 函数，
 * 使 Nova 脚本可以调用 Java 代码。
 */
public class CustomFunctionsExample {

    public static void main(String[] args) {
        System.out.println("=== Nova 自定义函数示例 ===\n");

        Nova nova = new Nova();

        // 1. 注册简单函数
        example1_SimpleFunction(nova);

        // 2. 注册带参数的函数
        example2_FunctionWithParams(nova);

        // 3. 注册可变参数函数
        example3_VarArgs(nova);

        // 4. 实际应用：日志记录
        example4_LoggingFunction(nova);

        System.out.println("\n=== 示例完成 ===");
    }

    /**
     * 示例 1: 注册简单函数
     */
    private static void example1_SimpleFunction(Nova nova) {
        System.out.println("--- 示例 1: 简单函数 ---");

        // 注册一个返回当前时间戳的函数（使用工厂方法）
        nova.defineVal("currentTimeMillis",
            NovaNativeFunction.create("currentTimeMillis", () -> new NovaLong(System.currentTimeMillis()))
        );

        // 注册一个返回随机数的函数（使用 lambda）
        nova.defineVal("randomInt",
            new NovaNativeFunction("randomInt", 1, (interp, args) -> {
                int max = args.get(0).asInt();
                return new NovaInt((int) (Math.random() * max));
            })
        );

        // 在 Nova 中调用
        Object time = nova.eval("currentTimeMillis()");
        System.out.println("当前时间戳: " + time);

        Object rand = nova.eval("randomInt(100)");
        System.out.println("随机数 (0-99): " + rand);

        System.out.println();
    }

    /**
     * 示例 2: 注册带参数的函数
     */
    private static void example2_FunctionWithParams(Nova nova) {
        System.out.println("--- 示例 2: 带参数的函数 ---");

        // 注册字符串格式化函数（使用工厂方法）
        nova.defineVal("format",
            NovaNativeFunction.create("format", (template, value) -> {
                return NovaString.of(template.asString().replace("{}", value.toString()));
            })
        );

        // 注册数学函数（使用 lambda）- 使用 power 避免与内置 pow 冲突
        nova.defineVal("power",
            new NovaNativeFunction("power", 2, (interp, args) -> {
                double base = args.get(0).asDouble();
                double exp = args.get(1).asDouble();
                return new NovaDouble(Math.pow(base, exp));
            })
        );

        // 注册 squareRoot 函数 - 避免与内置 sqrt 冲突
        nova.defineVal("squareRoot",
            NovaNativeFunction.create("squareRoot", arg -> new NovaDouble(Math.sqrt(arg.asDouble())))
        );

        // 在 Nova 中调用
        Object formatted = nova.eval("format(\"Value is: {}\", 42)");
        System.out.println(formatted);

        Object powerResult = nova.eval("power(2, 10)");
        System.out.println("2^10 = " + powerResult);

        Object sqrtVal = nova.eval("squareRoot(144)");
        System.out.println("squareRoot(144) = " + sqrtVal);

        System.out.println();
    }

    /**
     * 示例 3: 注册可变参数函数
     */
    private static void example3_VarArgs(Nova nova) {
        System.out.println("--- 示例 3: 可变参数 ---");

        // 注册一个连接多个字符串的函数（使用 createVararg）
        nova.defineVal("joinStrings",
            NovaNativeFunction.createVararg("joinStrings", (interp, args) -> {
                if (args.isEmpty()) {
                    return NovaString.of("");
                }
                // 第一个参数是分隔符
                String separator = args.get(0).asString();
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i < args.size(); i++) {
                    if (i > 1) sb.append(separator);
                    sb.append(args.get(i).toString());
                }
                return NovaString.of(sb.toString());
            })
        );

        // 注册一个求和函数
        nova.defineVal("sumAll",
            NovaNativeFunction.createVararg("sumAll", (interp, args) -> {
                int sum = 0;
                for (NovaValue arg : args) {
                    sum += arg.asInt();
                }
                return new NovaInt(sum);
            })
        );

        // 在 Nova 中调用
        Object joined = nova.eval("joinStrings(\", \", \"apple\", \"banana\", \"cherry\")");
        System.out.println("连接结果: " + joined);

        Object path = nova.eval("joinStrings(\"/\", \"home\", \"user\", \"documents\")");
        System.out.println("路径: " + path);

        Object sum = nova.eval("sumAll(1, 2, 3, 4, 5)");
        System.out.println("求和: " + sum);

        System.out.println();
    }

    /**
     * 示例 4: 实际应用 - 日志记录
     */
    private static void example4_LoggingFunction(Nova nova) {
        System.out.println("--- 示例 4: 日志记录 ---");

        // 注册日志函数
        nova.defineVal("log",
            NovaNativeFunction.createVararg("log", (interp, args) -> {
                String level = args.size() > 0 ? args.get(0).asString().toUpperCase() : "INFO";
                StringBuilder message = new StringBuilder();
                for (int i = 1; i < args.size(); i++) {
                    if (i > 1) message.append(" ");
                    message.append(args.get(i).toString());
                }
                System.out.println("[" + level + "] " + message);
                return NovaNull.UNIT;
            })
        );

        // 在 Nova 中使用日志
        nova.eval("log(\"info\", \"Application started\")");
        nova.eval("log(\"debug\", \"Processing item:\", 42)");
        nova.eval("log(\"warn\", \"Memory usage:\", 85, \"%\")");
        nova.eval("log(\"error\", \"Connection failed\")");

        System.out.println();
    }
}
