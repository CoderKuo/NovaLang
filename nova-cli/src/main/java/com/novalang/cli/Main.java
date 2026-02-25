package com.novalang.cli;

import nova.runtime.interpreter.NovaSecurityPolicy;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * NovaLang CLI 入口点（picocli）
 */
@Command(name = "nova", version = "NovaLang v0.1.0",
         mixinStandardHelpOptions = true,
         subcommands = {FmtCommand.class, BuildCommand.class})
public class Main implements Runnable {

    @Option(names = "--sandbox", description = "安全沙箱级别（strict, standard, unrestricted）")
    String sandbox;

    @Option(names = "--strict", description = "严格模式：类型检查警告升级为错误")
    boolean strict;

    @Option(names = "-e", description = "执行表达式")
    String expression;

    @Option(names = {"-c", "--compile"}, description = "编译文件")
    String compileFile;

    @Option(names = {"-o", "--output"}, description = "输出路径")
    String output;

    @Option(names = {"-r", "--run-compiled"}, description = "编译并运行")
    String runCompiledFile;

    @Parameters(description = "脚本文件及参数")
    String[] params;

    @Override
    public void run() {
        NovaSecurityPolicy policy = resolvePolicy(sandbox);

        if (expression != null) {
            new ScriptRunner(policy, strict).runExpression(expression);
        } else if (compileFile != null) {
            new CompileRunner(strict).compileFile(compileFile, output);
        } else if (runCompiledFile != null) {
            List<String> argsList = params != null ? Arrays.asList(params) : Collections.<String>emptyList();
            new CompileRunner(strict).compileAndRun(runCompiledFile, argsList);
        } else if (params != null && params.length > 0) {
            List<String> scriptArgs = params.length > 1
                    ? Arrays.asList(params).subList(1, params.length)
                    : Collections.<String>emptyList();
            new ScriptRunner(policy, strict).runScript(params[0], scriptArgs);
        } else {
            new ReplRunner(policy).run();
        }
    }

    static NovaSecurityPolicy resolvePolicy(String sandbox) {
        if (sandbox == null) return NovaSecurityPolicy.unrestricted();
        switch (sandbox.toLowerCase()) {
            case "strict":       return NovaSecurityPolicy.strict();
            case "standard":     return NovaSecurityPolicy.standard();
            case "unrestricted": return NovaSecurityPolicy.unrestricted();
            default:
                System.err.println("错误: 未知沙箱级别 '" + sandbox + "'（可选: strict, standard, unrestricted）");
                System.exit(1);
                return null; // unreachable
        }
    }

    public static void main(String[] args) {
        // Java 18+ (JEP 400) 默认 UTF-8，但 Windows 控制台可能仍用 GBK
        // 使用 native.encoding 获取操作系统原生编码，确保控制台正确显示中文
        String charsetName = getConsoleCharsetName();

        try {
            PrintStream out = new PrintStream(System.out, true, charsetName);
            PrintStream err = new PrintStream(System.err, true, charsetName);
            System.setOut(out);
            System.setErr(err);

            Charset consoleCharset = Charset.forName(charsetName);
            CommandLine cmd = new CommandLine(new Main());
            cmd.setOut(new PrintWriter(new OutputStreamWriter(out, consoleCharset), true));
            cmd.setErr(new PrintWriter(new OutputStreamWriter(err, consoleCharset), true));
            int exitCode = cmd.execute(args);
            System.exit(exitCode);
        } catch (UnsupportedEncodingException e) {
            // Fallback to default
            CommandLine cmd = new CommandLine(new Main());
            int exitCode = cmd.execute(args);
            System.exit(exitCode);
        }
    }

    /**
     * 获取控制台实际使用的字符编码名。
     * Java 18+ 默认 charset 为 UTF-8，但 Windows 控制台通常仍为 GBK/CP936。
     * native.encoding 属性（Java 17+）反映操作系统原生编码。
     */
    private static String getConsoleCharsetName() {
        String nativeEnc = System.getProperty("native.encoding");
        if (nativeEnc != null) {
            try {
                Charset.forName(nativeEnc);
                return nativeEnc;
            } catch (Exception ignored) {}
        }
        return Charset.defaultCharset().name();
    }
}
