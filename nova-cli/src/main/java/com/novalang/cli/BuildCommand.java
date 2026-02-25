package com.novalang.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * picocli build 子命令：编译项目（支持增量编译）
 */
@Command(name = "build", description = "编译项目（支持增量编译）")
public class BuildCommand implements Runnable {

    @Parameters(index = "0", defaultValue = ".", description = "源码目录")
    String sourceDir;

    @Option(names = {"-o", "--output"}, defaultValue = "build/classes", description = "输出目录（默认 build/classes）")
    String outputDir;

    @Option(names = "--jar", arity = "0..1", fallbackValue = "output.jar", description = "打包为 JAR 文件")
    String jarFile;

    @Override
    public void run() {
        new CompileRunner(false).buildProject(sourceDir, outputDir, jarFile);
    }
}
