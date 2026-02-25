package com.novalang.compiler.compiler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * 编译缓存，用于增量编译
 *
 * <p>记录每个源文件的内容哈希和生成的类名，用于判断文件是否需要重新编译。</p>
 */
public class CompileCache {
    /** 文件路径 -> 上次编译的内容哈希 */
    private final Map<String, String> fileHashes = new HashMap<String, String>();
    /** 文件路径 -> 生成的类名列表 */
    private final Map<String, List<String>> fileOutputs = new HashMap<String, List<String>>();

    /**
     * 检查文件是否有变化
     */
    public boolean isChanged(String filePath, String currentHash) {
        String cached = fileHashes.get(filePath);
        return cached == null || !cached.equals(currentHash);
    }

    /**
     * 更新缓存条目
     */
    public void update(String filePath, String hash, List<String> classNames) {
        fileHashes.put(filePath, hash);
        fileOutputs.put(filePath, new ArrayList<String>(classNames));
    }

    /**
     * 获取文件上次生成的类名列表
     */
    public List<String> getOutputClasses(String filePath) {
        List<String> classes = fileOutputs.get(filePath);
        return classes != null ? classes : Collections.<String>emptyList();
    }

    /**
     * 获取缓存中所有源文件路径
     */
    public Set<String> getAllFilePaths() {
        return new HashSet<String>(fileHashes.keySet());
    }

    /**
     * 移除缓存条目（源文件已删除时调用）
     */
    public void remove(String filePath) {
        fileHashes.remove(filePath);
        fileOutputs.remove(filePath);
    }

    /**
     * 保存缓存到文件
     */
    public void save(File cacheFile) throws IOException {
        cacheFile.getParentFile().mkdirs();
        PrintWriter writer = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(cacheFile), StandardCharsets.UTF_8));
        try {
            for (Map.Entry<String, String> entry : fileHashes.entrySet()) {
                String filePath = entry.getKey();
                String hash = entry.getValue();
                List<String> classes = fileOutputs.get(filePath);
                String classesStr = classes != null ? join(classes, ",") : "";
                // 格式: filePath\thash\tclass1,class2,...
                writer.println(filePath + "\t" + hash + "\t" + classesStr);
            }
        } finally {
            writer.close();
        }
    }

    /**
     * 从文件加载缓存
     */
    public static CompileCache load(File cacheFile) {
        CompileCache cache = new CompileCache();
        if (!cacheFile.exists()) {
            return cache;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(cacheFile), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\t", 3);
                if (parts.length >= 2) {
                    String filePath = parts[0];
                    String hash = parts[1];
                    List<String> classes = new ArrayList<String>();
                    if (parts.length == 3 && !parts[2].isEmpty()) {
                        for (String cls : parts[2].split(",")) {
                            classes.add(cls.trim());
                        }
                    }
                    cache.fileHashes.put(filePath, hash);
                    cache.fileOutputs.put(filePath, classes);
                }
            }
        } catch (IOException e) {
            // 缓存读取失败，返回空缓存
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (IOException ignored) {}
            }
        }
        return cache;
    }

    /**
     * 计算文件内容的 SHA-256 哈希
     */
    public static String computeHash(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private static String join(List<String> list, String separator) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(separator);
            sb.append(list.get(i));
        }
        return sb.toString();
    }
}
