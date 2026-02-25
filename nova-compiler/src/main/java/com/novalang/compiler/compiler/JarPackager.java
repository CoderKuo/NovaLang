package com.novalang.compiler.compiler;

import java.io.*;
import java.util.Map;
import java.util.jar.*;

/**
 * JAR 打包器
 *
 * <p>将编译生成的 .class 文件打包为可执行 JAR。</p>
 */
public class JarPackager {

    /**
     * 创建 JAR 文件
     *
     * @param classes   类名 -> 字节码
     * @param jarFile   输出 JAR 文件
     * @param mainClass 主类名（可选，用于 MANIFEST.MF 的 Main-Class）
     */
    public void createJar(Map<String, byte[]> classes, File jarFile, String mainClass) throws IOException {
        jarFile.getParentFile().mkdirs();

        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attrs.put(new Attributes.Name("Created-By"), "NovaLang Compiler");
        if (mainClass != null && !mainClass.isEmpty()) {
            attrs.put(Attributes.Name.MAIN_CLASS, mainClass);
        }

        JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarFile), manifest);
        try {
            for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
                String entryPath = entry.getKey().replace('.', '/') + ".class";
                JarEntry jarEntry = new JarEntry(entryPath);
                jos.putNextEntry(jarEntry);
                jos.write(entry.getValue());
                jos.closeEntry();
            }
        } finally {
            jos.close();
        }
    }

    /**
     * 从目录中打包所有 .class 文件为 JAR
     *
     * @param classDir  包含 .class 文件的目录
     * @param jarFile   输出 JAR 文件
     * @param mainClass 主类名（可选）
     */
    public void createJarFromDir(File classDir, File jarFile, String mainClass) throws IOException {
        jarFile.getParentFile().mkdirs();

        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attrs.put(new Attributes.Name("Created-By"), "NovaLang Compiler");
        if (mainClass != null && !mainClass.isEmpty()) {
            attrs.put(Attributes.Name.MAIN_CLASS, mainClass);
        }

        JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarFile), manifest);
        try {
            addDirToJar(classDir, classDir, jos);
        } finally {
            jos.close();
        }
    }

    private void addDirToJar(File baseDir, File dir, JarOutputStream jos) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                addDirToJar(baseDir, file, jos);
            } else if (file.getName().endsWith(".class")) {
                String entryPath = baseDir.toPath().relativize(file.toPath()).toString()
                        .replace('\\', '/');
                JarEntry entry = new JarEntry(entryPath);
                jos.putNextEntry(entry);
                FileInputStream fis = new FileInputStream(file);
                try {
                    byte[] buffer = new byte[4096];
                    int len;
                    while ((len = fis.read(buffer)) != -1) {
                        jos.write(buffer, 0, len);
                    }
                } finally {
                    fis.close();
                }
                jos.closeEntry();
            }
        }
    }
}
