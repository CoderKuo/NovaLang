package com.novalang.cli;

import nova.runtime.*;
import nova.runtime.interpreter.*;
import org.jline.reader.*;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;

/**
 * jline REPL 交互模式
 */
public class ReplRunner {

    private static final String VERSION = "0.1.0";

    private final NovaSecurityPolicy policy;
    private Interpreter interpreter;

    public ReplRunner(NovaSecurityPolicy policy) {
        this.policy = policy;
    }

    /**
     * 启动 REPL 交互模式
     */
    public void run() {
        interpreter = new Interpreter(policy);
        interpreter.setReplMode(true);

        printBanner();
        System.out.println("输入 :help 获取帮助，:quit 退出");
        System.out.println();

        try {
            Terminal terminal = TerminalBuilder.builder().system(true).build();
            LineReader reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .parser(new DefaultParser())
                    .variable(LineReader.SECONDARY_PROMPT_PATTERN, "... ")
                    .build();

            runLoop(reader);
        } catch (IOException e) {
            System.err.println("终端初始化失败: " + e.getMessage());
            // 回退到简单模式
            runFallbackLoop();
        }

        System.out.println("\n再见！");
    }

    /**
     * jline 主循环
     */
    private void runLoop(LineReader reader) {
        StringBuilder multilineBuffer = new StringBuilder();
        boolean inMultiline = false;

        while (true) {
            try {
                String prompt = inMultiline ? "... " : "nova> ";
                String line = reader.readLine(prompt);

                if (line == null) break;

                // REPL 命令
                if (!inMultiline && line.startsWith(":")) {
                    if (!handleReplCommand(line.trim())) break;
                    continue;
                }

                // 反斜杠续行
                if (line.endsWith("\\")) {
                    multilineBuffer.append(line, 0, line.length() - 1).append("\n");
                    inMultiline = true;
                    continue;
                }

                // 未闭合括号自动续行
                if (hasUnclosedBrackets(multilineBuffer.toString() + line)) {
                    multilineBuffer.append(line).append("\n");
                    inMultiline = true;
                    continue;
                }

                if (inMultiline) {
                    multilineBuffer.append(line);
                    line = multilineBuffer.toString();
                    multilineBuffer.setLength(0);
                    inMultiline = false;
                }

                if (line.trim().isEmpty()) continue;

                evaluateAndPrint(line);

            } catch (UserInterruptException e) {
                // Ctrl+C: 取消当前输入
                multilineBuffer.setLength(0);
                inMultiline = false;
            } catch (EndOfFileException e) {
                // Ctrl+D: 退出
                break;
            }
        }
    }

    /**
     * 回退循环（jline 初始化失败时使用 BufferedReader）
     */
    private void runFallbackLoop() {
        java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8));

        StringBuilder multilineBuffer = new StringBuilder();
        boolean inMultiline = false;

        while (true) {
            try {
                String prompt = inMultiline ? "... " : "nova> ";
                System.out.print(prompt);
                System.out.flush();

                String line = reader.readLine();
                if (line == null) break;

                if (!inMultiline && line.startsWith(":")) {
                    if (!handleReplCommand(line.trim())) break;
                    continue;
                }

                if (line.endsWith("\\")) {
                    multilineBuffer.append(line, 0, line.length() - 1).append("\n");
                    inMultiline = true;
                    continue;
                }

                if (hasUnclosedBrackets(multilineBuffer.toString() + line)) {
                    multilineBuffer.append(line).append("\n");
                    inMultiline = true;
                    continue;
                }

                if (inMultiline) {
                    multilineBuffer.append(line);
                    line = multilineBuffer.toString();
                    multilineBuffer.setLength(0);
                    inMultiline = false;
                }

                if (line.trim().isEmpty()) continue;

                evaluateAndPrint(line);

            } catch (IOException e) {
                System.err.println("读取输入时出错: " + e.getMessage());
                break;
            }
        }
    }

    /**
     * 检查是否有未闭合的括号
     */
    private boolean hasUnclosedBrackets(String text) {
        int braces = 0;
        int parens = 0;
        int brackets = 0;
        boolean inString = false;
        char stringChar = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (!inString && (c == '"' || c == '\'')) {
                inString = true;
                stringChar = c;
                continue;
            }
            if (inString) {
                if (c == stringChar && (i == 0 || text.charAt(i - 1) != '\\')) {
                    inString = false;
                }
                continue;
            }

            switch (c) {
                case '{': braces++; break;
                case '}': braces--; break;
                case '(': parens++; break;
                case ')': parens--; break;
                case '[': brackets++; break;
                case ']': brackets--; break;
            }
        }

        return braces > 0 || parens > 0 || brackets > 0;
    }

    /**
     * 处理 REPL 命令
     *
     * @return true 继续循环，false 退出
     */
    private boolean handleReplCommand(String command) {
        if (":quit".equals(command) || ":q".equals(command) || ":exit".equals(command)) {
            return false;
        }

        if (":help".equals(command) || ":h".equals(command)) {
            printReplHelp();
            return true;
        }

        if (":clear".equals(command) || ":c".equals(command)) {
            System.out.print("\033[H\033[2J");
            System.out.flush();
            return true;
        }

        if (":version".equals(command)) {
            System.out.println("NovaLang v" + VERSION);
            System.out.println("Java: " + System.getProperty("java.version"));
            System.out.println("JVM: " + System.getProperty("java.vm.name"));
            return true;
        }

        if (":reset".equals(command)) {
            interpreter = new Interpreter(policy);
            interpreter.setReplMode(true);
            System.out.println("环境已重置");
            return true;
        }

        if (":env".equals(command)) {
            System.out.println(interpreter.getGlobals());
            return true;
        }

        System.out.println("未知命令: " + command);
        System.out.println("输入 :help 获取帮助");
        return true;
    }

    /**
     * 求值并打印结果
     */
    private void evaluateAndPrint(String source) {
        try {
            NovaValue result = interpreter.evalRepl(source);

            if (result != null && !(result instanceof NovaNull && !((NovaNull) result).isNull())) {
                if (result instanceof NovaNull && ((NovaNull) result).isNull()) {
                    // null 值不打印
                } else if (result == NovaNull.UNIT) {
                    // Unit 值不打印
                } else {
                    if (result.isString()) {
                        System.out.println("\"" + result.asString() + "\"");
                    } else {
                        System.out.println(result);
                    }
                }
            }
        } catch (NovaRuntimeException e) {
            System.err.println("错误: " + e.getMessage());
        } catch (ControlFlow cf) {
            if (cf.getType() == ControlFlow.Type.RETURN && cf.getValue() != null) {
                System.out.println(cf.getValue());
            }
        } catch (Exception e) {
            System.err.println("错误: " + e.getMessage());
        }
    }

    private void printBanner() {
        System.out.println("  _   _                  _                       ");
        System.out.println(" | \\ | | _____   ____ _| |    __ _ _ __   __ _  ");
        System.out.println(" |  \\| |/ _ \\ \\ / / _` | |   / _` | '_ \\ / _` | ");
        System.out.println(" | |\\  | (_) \\ V / (_| | |__| (_| | | | | (_| | ");
        System.out.println(" |_| \\_|\\___/ \\_/ \\__,_|_____\\__,_|_| |_|\\__, | ");
        System.out.println("                                         |___/  ");
        System.out.println("NovaLang v" + VERSION + " - JVM 脚本语言");
        System.out.println();
    }

    private void printReplHelp() {
        System.out.println("REPL 命令:");
        System.out.println("  :help, :h        显示此帮助");
        System.out.println("  :quit, :q, :exit 退出 REPL");
        System.out.println("  :clear, :c       清屏");
        System.out.println("  :version         显示版本");
        System.out.println("  :reset           重置环境");
        System.out.println("  :env             显示当前环境变量");
        System.out.println();
        System.out.println("示例:");
        System.out.println("  val x = 42          定义不可变变量");
        System.out.println("  var y = \"hello\"     定义可变变量");
        System.out.println("  println(x + 1)      打印结果");
        System.out.println("  fun add(a, b) = a + b   定义函数");
        System.out.println("  [1, 2, 3].map { it * 2 }   列表操作");
        System.out.println();
        System.out.println("提示:");
        System.out.println("  - 行尾使用 \\ 可以输入多行");
        System.out.println("  - 未闭合的括号会自动进入多行模式");
        System.out.println("  - 直接输入表达式即可求值");
    }
}
