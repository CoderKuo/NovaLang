package com.novalang.compiler.compiler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * 增量编译器
 *
 * <p>通过缓存文件哈希值，只重新编译有变化的源文件。</p>
 *
 * <p><b>已知限制</b>：当前仅基于单文件内容哈希判断是否重新编译，
 * 不追踪跨文件依赖（import 关系）。如果 A 导入 B，B 发生变更，
 * A 不会自动重新编译。需要完整重编译时，请删除缓存目录 ({@value CACHE_DIR})。</p>
 *
 * TODO: 解析 import 声明构建依赖图，变更时传播到依赖方
 */
public class IncrementalCompiler {
    private static final String CACHE_DIR = ".nova-cache";
    private static final String CACHE_FILE = "compile.cache";

    private final NovaCompilerApi compiler;
    private CompileCache cache;

    public IncrementalCompiler(NovaCompilerApi compiler) {
        this.compiler = compiler;
    }

    /**
     * 编译项目（增量）
     *
     * @param sourceDir 源码目录
     * @param outputDir 输出目录
     * @return 所有生成的类（类名 -> 字节码）
     */
    public Map<String, byte[]> compileProject(File sourceDir, File outputDir) throws IOException {
        if (!sourceDir.exists() || !sourceDir.isDirectory()) {
            throw new IOException("源码目录不存在: " + sourceDir.getAbsolutePath());
        }

        outputDir.mkdirs();

        // 加载缓存
        File cacheFile = new File(outputDir, CACHE_DIR + File.separator + CACHE_FILE);
        cache = CompileCache.load(cacheFile);

        // 扫描所有 .nova 文件
        List<File> sourceFiles = new ArrayList<File>();
        scanNovaFiles(sourceDir, sourceFiles);

        Map<String, byte[]> allClasses = new HashMap<String, byte[]>();
        List<String> failedFiles = new ArrayList<String>();
        Set<String> currentSourcePaths = new HashSet<String>();
        int compiledCount = 0;
        int skippedCount = 0;

        for (File file : sourceFiles) {
            String filePath = file.getAbsolutePath();
            currentSourcePaths.add(filePath);
            String source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            String hash = CompileCache.computeHash(source);

            if (cache.isChanged(filePath, hash)) {
                // 需要重新编译
                try {
                    Map<String, byte[]> classes = compiler.compile(source, file.getName());
                    allClasses.putAll(classes);

                    // 保存 .class 文件
                    for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
                        saveClass(outputDir, entry.getKey(), entry.getValue());
                    }

                    // 更新缓存
                    cache.update(filePath, hash, new ArrayList<String>(classes.keySet()));
                    compiledCount++;
                    System.out.println("  编译: " + file.getName());
                } catch (Exception e) {
                    System.err.println("  编译失败: " + file.getName() + " - " + e.getMessage());
                    failedFiles.add(file.getName());
                }
            } else {
                // 文件未变化，从 outputDir 加载已有 .class
                for (String className : cache.getOutputClasses(filePath)) {
                    File classFile = new File(outputDir,
                            className.replace('.', File.separatorChar) + ".class");
                    if (classFile.exists()) {
                        allClasses.put(className, Files.readAllBytes(classFile.toPath()));
                    }
                }
                skippedCount++;
            }
        }

        // 清理已删除源文件的缓存和旧 .class
        for (String cachedPath : cache.getAllFilePaths()) {
            if (!currentSourcePaths.contains(cachedPath)) {
                for (String className : cache.getOutputClasses(cachedPath)) {
                    File classFile = new File(outputDir,
                            className.replace('.', File.separatorChar) + ".class");
                    if (classFile.exists()) {
                        classFile.delete();
                    }
                }
                cache.remove(cachedPath);
            }
        }

        // 保存缓存
        cache.save(cacheFile);

        System.out.println("编译完成: " + compiledCount + " 个文件已编译, "
                + skippedCount + " 个文件未变化");

        if (!failedFiles.isEmpty()) {
            throw new IOException("编译失败的文件: " + failedFiles);
        }

        return allClasses;
    }

    /**
     * 递归扫描 .nova 文件
     */
    private void scanNovaFiles(File dir, List<File> result) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                scanNovaFiles(file, result);
            } else if (file.getName().endsWith(".nova")) {
                result.add(file);
            }
        }
    }

    /**
     * 保存 .class 文件
     */
    private void saveClass(File outputDir, String className, byte[] bytecode) throws IOException {
        String path = className.replace('.', File.separatorChar) + ".class";
        File classFile = new File(outputDir, path);
        classFile.getParentFile().mkdirs();
        FileOutputStream fos = new FileOutputStream(classFile);
        try {
            fos.write(bytecode);
        } finally {
            fos.close();
        }
    }
}
