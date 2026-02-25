package com.novalang.cli;

import com.novalang.compiler.formatter.FormatConfig;
import com.novalang.compiler.formatter.NovaFormatter;
import com.novalang.compiler.lexer.Lexer;
import com.novalang.compiler.parser.Parser;
import com.novalang.compiler.ast.decl.ImportDecl;
import com.novalang.compiler.ast.decl.Program;
import com.novalang.ir.NovaIrCompiler;
import com.novalang.ir.hir.HirDecl;
import com.novalang.ir.hir.decl.HirClass;
import com.novalang.ir.hir.decl.HirModule;
import com.novalang.ir.lowering.AstToHirLowering;
import nova.runtime.interpreter.NovaRuntimeException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * 编译、构建、格式化执行器
 */
public class CompileRunner {

    private final boolean strict;

    public CompileRunner(boolean strict) {
        this.strict = strict;
    }

    /**
     * 编译文件到字节码
     */
    public void compileFile(String filePath, String outputPath) {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            System.err.println("错误: 文件不存在 - " + filePath);
            System.exit(1);
        }

        try {
            String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);

            if (outputPath == null) {
                outputPath = ".";
            }

            NovaIrCompiler irCompiler = new NovaIrCompiler();
            irCompiler.compileAndSave(source, path.getFileName().toString(), new File(outputPath));

            System.out.println("编译成功！");
        } catch (Exception e) {
            System.err.println("编译错误: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * 编译并直接运行（不输出文件）
     */
    public void compileAndRun(String filePath, List<String> scriptArgs) {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            System.err.println("错误: 文件不存在 - " + filePath);
            System.exit(1);
        }

        try {
            String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);

            // 语义分析（类型检查）
            new ScriptRunner(null, strict).runSemanticAnalysis(source, path.getFileName().toString());

            NovaIrCompiler irCompiler = new NovaIrCompiler();

            // 解析主文件 AST，提取 import 声明
            Lexer lexer = new Lexer(source, path.getFileName().toString());
            Parser parser = new Parser(lexer, path.getFileName().toString());
            Program program = parser.parse();

            // 递归编译导入的 Nova 模块，同时收集外部类的 HIR 声明
            Map<String, byte[]> allBytecodes = new HashMap<>();
            List<HirClass> externalClasses = new ArrayList<>();
            Path scriptDir = path.toAbsolutePath().getParent();
            Set<Path> compiled = new HashSet<>();
            compileImportedModules(program, scriptDir, irCompiler, allBytecodes, compiled, externalClasses);

            // 将外部类信息注入主模块编译管线，确保方法描述符正确
            irCompiler.getPipeline().setExternalClasses(externalClasses);

            // 编译主文件
            allBytecodes.putAll(irCompiler.compile(source, path.getFileName().toString()));
            Map<String, Class<?>> classes = loadClasses(allBytecodes);

            // 查找包含 main() 方法的类并执行
            for (Map.Entry<String, Class<?>> entry : classes.entrySet()) {
                try {
                    java.lang.reflect.Method mainMethod = entry.getValue().getMethod("main");
                    mainMethod.invoke(null);
                    return;
                } catch (NoSuchMethodException e) {
                    // 继续查找下一个类
                }
            }
            System.err.println("错误: 未找到 main 方法");
            System.exit(1);

        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof NovaRuntimeException) {
                NovaRuntimeException nre = (NovaRuntimeException) cause;
                System.err.println("运行时错误: " + nre.getMessage());
                if (nre.getNovaStackTrace() != null) {
                    System.err.println(nre.getNovaStackTrace());
                }
            } else {
                System.err.println("运行时错误: " + (cause != null ? cause.getMessage() : e.getMessage()));
                if (cause != null) cause.printStackTrace();
            }
            System.exit(1);
        } catch (Exception e) {
            System.err.println("编译运行错误: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * 格式化文件
     */
    public void formatFile(String filePath, int indentSize, boolean useTabs, int maxWidth) {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            System.err.println("错误: 文件不存在 - " + filePath);
            System.exit(1);
        }

        try {
            String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);

            FormatConfig config = new FormatConfig();
            config.setIndentSize(indentSize);
            config.setUseSpaces(!useTabs);
            config.setMaxLineWidth(maxWidth);

            Lexer lexer = new Lexer(source, path.getFileName().toString());
            Parser parser = new Parser(lexer, path.getFileName().toString());
            Program program = parser.parse();

            NovaFormatter formatter = new NovaFormatter();
            String formatted = formatter.format(program, config);

            Files.write(path, formatted.getBytes(StandardCharsets.UTF_8));
            System.out.println("已格式化: " + filePath);
        } catch (Exception e) {
            System.err.println("格式化错误: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * 构建项目（支持增量编译 + JAR 打包）
     */
    public void buildProject(String sourceDir, String outputDir, String jarFile) {
        try {
            com.novalang.compiler.compiler.IncrementalCompiler incrementalCompiler =
                    new com.novalang.compiler.compiler.IncrementalCompiler(new NovaIrCompiler());

            Map<String, byte[]> results = incrementalCompiler.compileProject(
                    new File(sourceDir), new File(outputDir));

            System.out.println("编译完成: " + results.size() + " 个类");

            if (jarFile != null) {
                com.novalang.compiler.compiler.JarPackager packager =
                        new com.novalang.compiler.compiler.JarPackager();
                packager.createJar(results, new File(jarFile), null);
                System.out.println("已生成: " + jarFile);
            }
        } catch (Exception e) {
            System.err.println("构建错误: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * 递归编译导入的 Nova 模块文件，同时收集模块中的 HirClass 声明。
     */
    private void compileImportedModules(Program program, Path scriptDir,
            NovaIrCompiler compiler, Map<String, byte[]> allBytecodes, Set<Path> compiled,
            List<HirClass> externalClasses) {
        for (ImportDecl imp : program.getImports()) {
            if (imp.isJava() || imp.isStatic()) continue;

            List<String> parts = imp.getName().getParts();
            if (parts.size() < 2) continue;

            String modulePath = String.join(File.separator,
                    parts.subList(0, parts.size() - 1)) + ".nova";
            Path moduleFile = scriptDir.resolve(modulePath).normalize();

            if (!Files.exists(moduleFile) || compiled.contains(moduleFile)) continue;
            compiled.add(moduleFile);

            try {
                String moduleSource = new String(Files.readAllBytes(moduleFile), StandardCharsets.UTF_8);

                Lexer mLexer = new Lexer(moduleSource, moduleFile.getFileName().toString());
                Parser mParser = new Parser(mLexer, moduleFile.getFileName().toString());
                Program moduleProgram = mParser.parse();
                compileImportedModules(moduleProgram, moduleFile.getParent(), compiler,
                        allBytecodes, compiled, externalClasses);

                HirModule hirModule = new AstToHirLowering().lower(moduleProgram);
                for (HirDecl decl : hirModule.getDeclarations()) {
                    if (decl instanceof HirClass) {
                        externalClasses.add((HirClass) decl);
                    }
                }

                allBytecodes.putAll(compiler.compile(moduleSource, moduleFile.getFileName().toString()));
            } catch (IOException e) {
                System.err.println("警告: 无法读取模块文件 " + moduleFile + ": " + e.getMessage());
            }
        }
    }

    /**
     * 从字节码 Map 加载类
     */
    private Map<String, Class<?>> loadClasses(Map<String, byte[]> bytecodes) {
        ClassLoader loader = new ClassLoader(CompileRunner.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] b = bytecodes.get(name);
                if (b != null) return defineClass(name, b, 0, b.length);
                throw new ClassNotFoundException(name);
            }
        };
        Map<String, Class<?>> loaded = new HashMap<>();
        for (String className : bytecodes.keySet()) {
            try {
                String dotName = className.replace('/', '.');
                loaded.put(dotName, loader.loadClass(dotName));
            } catch (ClassNotFoundException e) {
                throw new RuntimeException("Failed to load class: " + className, e);
            }
        }
        return loaded;
    }
}
