package com.novalang.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * picocli fmt 子命令：格式化源码文件
 */
@Command(name = "fmt", description = "格式化源码文件")
public class FmtCommand implements Runnable {

    @Parameters(index = "0", description = "源码文件路径")
    String file;

    @Option(names = "--indent-size", defaultValue = "4", description = "缩进空格数（默认 4）")
    int indentSize;

    @Option(names = "--use-tabs", description = "使用 Tab 缩进")
    boolean useTabs;

    @Option(names = "--max-width", defaultValue = "120", description = "最大行宽（默认 120）")
    int maxWidth;

    @Override
    public void run() {
        new CompileRunner(false).formatFile(file, indentSize, useTabs, maxWidth);
    }
}
