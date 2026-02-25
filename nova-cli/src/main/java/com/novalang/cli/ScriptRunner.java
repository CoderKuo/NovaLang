package com.novalang.cli;

import com.novalang.compiler.analysis.AnalysisResult;
import com.novalang.compiler.analysis.SemanticAnalyzer;
import com.novalang.compiler.analysis.SemanticDiagnostic;
import com.novalang.compiler.lexer.Lexer;
import com.novalang.compiler.parser.ParseException;
import com.novalang.compiler.parser.Parser;
import com.novalang.compiler.ast.decl.Program;
import nova.runtime.*;
import nova.runtime.interpreter.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 脚本和表达式执行器
 */
public class ScriptRunner {

    private final NovaSecurityPolicy policy;
    private final boolean strict;

    public ScriptRunner(NovaSecurityPolicy policy, boolean strict) {
        this.policy = policy;
        this.strict = strict;
    }

    /**
     * 执行脚本文件
     */
    public void runScript(String filePath, List<String> scriptArgs) {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            System.err.println("错误: 文件不存在 - " + filePath);
            System.exit(1);
        }

        if (!Files.isReadable(path)) {
            System.err.println("错误: 无法读取文件 - " + filePath);
            System.exit(1);
        }

        String source = null;
        try {
            source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);

            Interpreter interpreter = new Interpreter(policy);

            // 设置脚本参数
            NovaList args = new NovaList();
            for (String arg : scriptArgs) {
                args.add(NovaString.of(arg));
            }
            interpreter.getGlobals().defineVal("args", args);

            // 设置脚本基路径，启用模块加载
            Path scriptDir = path.toAbsolutePath().getParent();
            interpreter.setScriptBasePath(scriptDir);

            // 语义分析（类型检查）
            runSemanticAnalysis(source, filePath);

            // 执行脚本（支持顶层语句）
            interpreter.eval(source, filePath);

        } catch (ParseException e) {
            System.err.println("语法错误: " + e.getMessage());
            if (e.getToken() != null && source != null) {
                printSourceLocation(source, filePath, e.getToken().getLine(),
                        e.getToken().getColumn(), e.getToken().getLexeme().length());
            }
            System.exit(1);
        } catch (NovaRuntimeException e) {
            System.err.println("运行时错误: " + e.getMessage());
            if (e.getNovaStackTrace() != null) {
                System.err.println(e.getNovaStackTrace());
            }
            System.exit(1);
        } catch (Exception e) {
            System.err.println("错误: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * 执行单个表达式
     */
    public void runExpression(String expression) {
        try {
            Interpreter interpreter = new Interpreter(policy);
            NovaValue result = interpreter.eval(expression, "<cmdline>");
            if (result != null && !(result instanceof NovaNull)) {
                System.out.println(result);
            }
        } catch (NovaRuntimeException e) {
            System.err.println("运行时错误: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("错误: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * 运行语义分析并输出诊断信息。
     * 默认诊断为 WARNING 输出到 stderr 但不阻断执行。
     * --strict 模式下有 ERROR 时阻断执行。
     */
    void runSemanticAnalysis(String source, String fileName) {
        try {
            Lexer lexer = new Lexer(source, fileName);
            Parser parser = new Parser(lexer, fileName);
            Program program = parser.parse();

            SemanticAnalyzer analyzer = new SemanticAnalyzer();
            AnalysisResult result = analyzer.analyze(program);

            boolean hasError = false;
            for (SemanticDiagnostic diag : result.getDiagnostics()) {
                SemanticDiagnostic.Severity severity = diag.getSeverity();
                // --strict 模式下 WARNING 升级为 ERROR
                if (strict && severity == SemanticDiagnostic.Severity.WARNING) {
                    severity = SemanticDiagnostic.Severity.ERROR;
                }
                if (severity == SemanticDiagnostic.Severity.ERROR) {
                    hasError = true;
                }
                String prefix;
                switch (severity) {
                    case ERROR:   prefix = "错误"; break;
                    case WARNING: prefix = "警告"; break;
                    case INFO:    prefix = "信息"; break;
                    case HINT:    prefix = "提示"; break;
                    default:      prefix = "诊断"; break;
                }
                String location = "";
                if (diag.getLocation() != null) {
                    location = " (" + fileName + ":" + diag.getLocation().getLine() +
                               ":" + diag.getLocation().getColumn() + ")";
                }
                System.err.println("[" + prefix + "]" + location + " " + diag.getMessage());
            }

            if (strict && hasError) {
                System.err.println("严格模式: 存在类型错误，中止执行");
                System.exit(1);
            }
        } catch (Exception e) {
            // 语义分析失败不阻断执行
        }
    }

    /**
     * 打印源码位置指示（文件名:行:列 + 源码行 + 下划线指针）
     */
    static void printSourceLocation(String source, String fileName, int line, int column, int length) {
        System.err.println("  --> " + fileName + ":" + line + ":" + column);
        String[] lines = source.split("\n", -1);
        if (line >= 1 && line <= lines.length) {
            String lineText = lines[line - 1];
            String lineNum = String.valueOf(line);
            System.err.println("   |");
            System.err.println(" " + lineNum + " | " + lineText);
            StringBuilder pointer = new StringBuilder();
            for (int i = 0; i < lineNum.length() + 1; i++) pointer.append(' ');
            pointer.append("| ");
            for (int i = 1; i < column; i++) pointer.append(' ');
            for (int i = 0; i < Math.max(1, length); i++) pointer.append('^');
            System.err.println(pointer.toString());
        }
    }
}
