package com.novalang.runtime.interpreter.stdlib;

import com.novalang.runtime.*;
import com.novalang.runtime.interpreter.NovaRuntimeException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * nova.io 模块的编译模式运行时实现。
 *
 * <p>编译后的字节码通过 INVOKESTATIC 调用此类的静态方法。
 * 方法签名全部为 Object 参数/返回值。</p>
 *
 * <p>编译模式使用原生 Java 类型（String/Boolean/Long 等），
 * 返回值不使用 NovaValue 包装，以兼容编译路径的运算符和类型转换。</p>
 */
public final class StdlibIOCompiled {

    private StdlibIOCompiled() {}

    // ============ 文件读写 ============

    public static Object readFile(Object path) {
        try {
            return new String(Files.readAllBytes(Paths.get(str(path))), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new NovaRuntimeException("readFile failed: " + e.getMessage());
        }
    }

    public static Object writeFile(Object path, Object content) {
        try {
            Files.write(Paths.get(str(path)), str(content).getBytes(StandardCharsets.UTF_8));
            return null;
        } catch (IOException e) {
            throw new NovaRuntimeException("writeFile failed: " + e.getMessage());
        }
    }

    public static Object appendFile(Object path, Object content) {
        try {
            Files.write(Paths.get(str(path)), str(content).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return null;
        } catch (IOException e) {
            throw new NovaRuntimeException("appendFile failed: " + e.getMessage());
        }
    }

    public static Object readLines(Object path) {
        try {
            List<String> lines = Files.readAllLines(Paths.get(str(path)), StandardCharsets.UTF_8);
            return new ArrayList<>(lines);
        } catch (IOException e) {
            throw new NovaRuntimeException("readLines failed: " + e.getMessage());
        }
    }

    public static Object writeLines(Object path, Object lines) {
        try {
            List<?> list = (List<?>) lines;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(System.lineSeparator());
                sb.append(str(list.get(i)));
            }
            Files.write(Paths.get(str(path)), sb.toString().getBytes(StandardCharsets.UTF_8));
            return null;
        } catch (IOException e) {
            throw new NovaRuntimeException("writeLines failed: " + e.getMessage());
        }
    }

    public static Object readBytes(Object path) {
        try {
            byte[] bytes = Files.readAllBytes(Paths.get(str(path)));
            List<Integer> result = new ArrayList<>(bytes.length);
            for (byte b : bytes) result.add(b & 0xFF);
            return result;
        } catch (IOException e) {
            throw new NovaRuntimeException("readBytes failed: " + e.getMessage());
        }
    }

    public static Object writeBytes(Object path, Object bytesVal) {
        try {
            List<?> list = (List<?>) bytesVal;
            byte[] bytes = new byte[list.size()];
            for (int i = 0; i < list.size(); i++) bytes[i] = ((Number) list.get(i)).byteValue();
            Files.write(Paths.get(str(path)), bytes);
            return null;
        } catch (IOException e) {
            throw new NovaRuntimeException("writeBytes failed: " + e.getMessage());
        }
    }

    // ============ 文件操作 ============

    public static Object fileExists(Object path) {
        return Files.exists(Paths.get(str(path)));
    }

    public static Object deleteFile(Object path) {
        try {
            Files.deleteIfExists(Paths.get(str(path)));
            return null;
        } catch (IOException e) {
            throw new NovaRuntimeException("deleteFile failed: " + e.getMessage());
        }
    }

    public static Object copyFile(Object src, Object dst) {
        try {
            Files.copy(Paths.get(str(src)), Paths.get(str(dst)), StandardCopyOption.REPLACE_EXISTING);
            return null;
        } catch (IOException e) {
            throw new NovaRuntimeException("copyFile failed: " + e.getMessage());
        }
    }

    public static Object moveFile(Object src, Object dst) {
        try {
            Files.move(Paths.get(str(src)), Paths.get(str(dst)), StandardCopyOption.REPLACE_EXISTING);
            return null;
        } catch (IOException e) {
            throw new NovaRuntimeException("moveFile failed: " + e.getMessage());
        }
    }

    // ============ 目录操作 ============

    public static Object listDir(Object path) {
        try (Stream<Path> stream = Files.list(Paths.get(str(path)))) {
            List<String> result = new ArrayList<>();
            stream.forEach(p -> result.add(p.toString()));
            return result;
        } catch (IOException e) {
            throw new NovaRuntimeException("listDir failed: " + e.getMessage());
        }
    }

    public static Object mkdir(Object path) {
        try {
            Files.createDirectory(Paths.get(str(path)));
            return null;
        } catch (IOException e) {
            throw new NovaRuntimeException("mkdir failed: " + e.getMessage());
        }
    }

    public static Object mkdirs(Object path) {
        try {
            Files.createDirectories(Paths.get(str(path)));
            return null;
        } catch (IOException e) {
            throw new NovaRuntimeException("mkdirs failed: " + e.getMessage());
        }
    }

    // ============ 文件信息 ============

    public static Object isFile(Object path) {
        return Files.isRegularFile(Paths.get(str(path)));
    }

    public static Object isDir(Object path) {
        return Files.isDirectory(Paths.get(str(path)));
    }

    public static Object fileSize(Object path) {
        try {
            return Files.size(Paths.get(str(path)));
        } catch (IOException e) {
            throw new NovaRuntimeException("fileSize failed: " + e.getMessage());
        }
    }

    // ============ 路径操作 ============

    public static Object pathJoin(Object path1, Object path2) {
        return Paths.get(str(path1)).resolve(str(path2)).toString();
    }

    public static Object fileName(Object path) {
        Path name = Paths.get(str(path)).getFileName();
        return name != null ? name.toString() : null;
    }

    public static Object fileExtension(Object path) {
        String name = Paths.get(str(path)).getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : "";
    }

    public static Object parentDir(Object path) {
        Path parent = Paths.get(str(path)).getParent();
        return parent != null ? parent.toString() : null;
    }

    public static Object absolutePath(Object path) {
        return Paths.get(str(path)).toAbsolutePath().toString();
    }

    public static Object currentDir() {
        return System.getProperty("user.dir");
    }

    public static Object tempDir() {
        return System.getProperty("java.io.tmpdir");
    }

    public static Object tempFile() {
        try {
            return Files.createTempFile("nova", ".tmp").toString();
        } catch (IOException e) {
            throw new NovaRuntimeException("tempFile failed: " + e.getMessage());
        }
    }

    // ============ Hutool 启发 ============

    /** 递归列出目录下所有文件（返回绝对路径列表） */
    public static Object loopFiles(Object dirPath) {
        java.io.File dir = new java.io.File(str(dirPath));
        List<String> result = new java.util.ArrayList<>();
        walkFiles(dir, result);
        return result;
    }

    /** 递归列出目录下匹配扩展名的文件 */
    public static Object loopFiles(Object dirPath, Object extension) {
        java.io.File dir = new java.io.File(str(dirPath));
        String ext = str(extension).toLowerCase();
        List<String> result = new java.util.ArrayList<>();
        walkFilesFiltered(dir, ext, result);
        return result;
    }

    private static void walkFiles(java.io.File dir, List<String> result) {
        if (!dir.isDirectory()) return;
        java.io.File[] files = dir.listFiles();
        if (files == null) return;
        for (java.io.File f : files) {
            if (f.isFile()) result.add(f.getAbsolutePath());
            else if (f.isDirectory()) walkFiles(f, result);
        }
    }

    private static void walkFilesFiltered(java.io.File dir, String ext, List<String> result) {
        if (!dir.isDirectory()) return;
        java.io.File[] files = dir.listFiles();
        if (files == null) return;
        for (java.io.File f : files) {
            if (f.isFile() && f.getName().toLowerCase().endsWith("." + ext)) {
                result.add(f.getAbsolutePath());
            } else if (f.isDirectory()) {
                walkFilesFiltered(f, ext, result);
            }
        }
    }

    /** 统计文件行数 */
    public static Object fileLineCount(Object path) {
        try (java.io.BufferedReader reader = java.nio.file.Files.newBufferedReader(
                java.nio.file.Paths.get(str(path)), java.nio.charset.StandardCharsets.UTF_8)) {
            int count = 0;
            while (reader.readLine() != null) count++;
            return count;
        } catch (IOException e) {
            throw new NovaRuntimeException("fileLineCount failed: " + e.getMessage());
        }
    }

    /** 比较两个文件内容是否相同 */
    public static Object contentEquals(Object path1, Object path2) {
        try {
            byte[] b1 = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(str(path1)));
            byte[] b2 = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(str(path2)));
            return java.util.Arrays.equals(b1, b2);
        } catch (IOException e) {
            throw new NovaRuntimeException("contentEquals failed: " + e.getMessage());
        }
    }

    /** 获取文件最后修改时间（毫秒时间戳） */
    public static Object lastModified(Object path) {
        return new java.io.File(str(path)).lastModified();
    }

    // ============ 工具方法 ============

    private static String str(Object o) {
        if (o instanceof String) return (String) o;
        return ((NovaValue) o).asString();
    }
}
