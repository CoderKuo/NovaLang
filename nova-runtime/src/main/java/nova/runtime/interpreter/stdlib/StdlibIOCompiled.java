package nova.runtime.interpreter.stdlib;

import nova.runtime.*;
import nova.runtime.interpreter.NovaRuntimeException;

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

    // ============ 工具方法 ============

    private static String str(Object o) {
        if (o instanceof String) return (String) o;
        return ((NovaValue) o).asString();
    }
}
