package nova.example;

import nova.runtime.Nova;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 脚本文件执行示例
 *
 * 展示如何从文件或资源加载并执行 Nova 脚本。
 */
public class ScriptFileExample {

    public static void main(String[] args) {
        System.out.println("=== Nova 脚本文件执行示例 ===\n");

        Nova nova = new Nova();

        // 1. 从资源加载脚本
        example1_LoadFromResource(nova);

        // 2. 从字符串执行脚本
        example2_ExecuteScript(nova);

        // 3. 创建临时脚本文件并执行
        example3_TempScriptFile(nova);

        System.out.println("\n=== 示例完成 ===");
    }

    /**
     * 示例 1: 从类路径资源加载脚本
     */
    private static void example1_LoadFromResource(Nova nova) {
        System.out.println("--- 示例 1: 从资源加载 ---");

        try (InputStream is = ScriptFileExample.class.getResourceAsStream("/scripts/hello.nova")) {
            if (is != null) {
                String script = readInputStream(is);
                System.out.println("脚本内容:");
                System.out.println("---");
                System.out.println(script);
                System.out.println("---");
                System.out.println("执行结果:");
                nova.eval(script);
            } else {
                System.out.println("未找到脚本资源");
            }
        } catch (IOException e) {
            System.err.println("读取资源失败: " + e.getMessage());
        }

        System.out.println();
    }

    /**
     * 示例 2: 从字符串执行多行脚本
     */
    private static void example2_ExecuteScript(Nova nova) {
        System.out.println("--- 示例 2: 执行脚本字符串 ---");

        String script = String.join("\n",
            "// 定义一个简单的计数器",
            "class Counter(var value: Int = 0) {",
            "    fun increment() { value = value + 1 }",
            "    fun decrement() { value = value - 1 }",
            "    fun get() { return value }",
            "}",
            "",
            "val counter = Counter(10)",
            "counter.increment()",
            "counter.increment()",
            "counter.decrement()",
            "counter.get()"
        );

        System.out.println("脚本:");
        System.out.println(script);
        System.out.println();

        Object result = nova.eval(script);
        System.out.println("最终计数器值: " + result);

        System.out.println();
    }

    /**
     * 示例 3: 创建临时脚本文件并执行
     */
    private static void example3_TempScriptFile(Nova nova) {
        System.out.println("--- 示例 3: 临时脚本文件 ---");

        try {
            // 创建临时脚本文件
            Path tempFile = Files.createTempFile("nova_script_", ".nova");

            String script = String.join("\n",
                "// 临时脚本",
                "fun factorial(n: Int): Int {",
                "    if (n <= 1) return 1",
                "    return n * factorial(n - 1)",
                "}",
                "",
                "val results = []",
                "for (i in 1..10) {",
                "    results.add(factorial(i))",
                "}",
                "results"
            );

            // 写入脚本内容
            Files.write(tempFile, script.getBytes(StandardCharsets.UTF_8));
            System.out.println("临时文件: " + tempFile);

            // 使用 Nova API 执行文件
            Object result = nova.evalFile(tempFile.toFile());
            System.out.println("阶乘结果 (1-10): " + result);

            // 清理临时文件
            Files.delete(tempFile);

        } catch (IOException e) {
            System.err.println("文件操作失败: " + e.getMessage());
        }

        System.out.println();
    }

    /**
     * 辅助方法：读取输入流为字符串
     */
    private static String readInputStream(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }
}
