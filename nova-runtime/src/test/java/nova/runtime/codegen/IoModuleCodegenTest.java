package nova.runtime.codegen;

import com.novalang.ir.NovaIrCompiler;
import nova.runtime.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 编译模式 import nova.io.* 测试。
 *
 * <p>验证 BuiltinModuleExports 反射发现 + INVOKESTATIC StdlibIOCompiled 路径。</p>
 */
@DisplayName("编译模式: import nova.io.*")
class IoModuleCodegenTest {

    private static final NovaIrCompiler compiler = new NovaIrCompiler();

    static {
        compiler.setScriptMode(true);
    }

    @TempDir
    Path tempDir;

    private Object compileAndRun(String code) throws Exception {
        Map<String, Class<?>> loaded = compiler.compileAndLoad(code, "test.nova");
        Class<?> module = loaded.get("$Module");
        assertNotNull(module, "编译后应生成 $Module 类");
        Method main = module.getDeclaredMethod("main");
        main.setAccessible(true);

        NovaScriptContext.init(new HashMap<>());
        try {
            return main.invoke(null);
        } finally {
            NovaScriptContext.clear();
        }
    }

    private static String asString(Object result) {
        if (result instanceof NovaValue) return ((NovaValue) result).asString();
        return String.valueOf(result);
    }

    private static boolean asBool(Object result) {
        if (result instanceof NovaValue) return ((NovaValue) result).asBoolean();
        return (Boolean) result;
    }

    // ============ 文件读写 ============

    @Test
    @DisplayName("writeFile + readFile")
    void testWriteAndReadFile() throws Exception {
        Path file = tempDir.resolve("test.txt");
        String code = "import nova.io.*\n" +
                "writeFile(\"" + esc(file) + "\", \"hello nova\")\n" +
                "readFile(\"" + esc(file) + "\")";
        Object result = compileAndRun(code);
        assertEquals("hello nova", asString(result));
    }

    @Test
    @DisplayName("appendFile")
    void testAppendFile() throws Exception {
        Path file = tempDir.resolve("append.txt");
        Files.write(file, "aaa".getBytes(StandardCharsets.UTF_8));
        String code = "import nova.io.*\n" +
                "appendFile(\"" + esc(file) + "\", \"bbb\")\n" +
                "readFile(\"" + esc(file) + "\")";
        Object result = compileAndRun(code);
        assertEquals("aaabbb", asString(result));
    }

    @Test
    @DisplayName("readLines")
    void testReadLines() throws Exception {
        Path file = tempDir.resolve("lines.txt");
        Files.write(file, "a\nb\nc".getBytes(StandardCharsets.UTF_8));
        // readLines 返回 ArrayList<String>，编译模式下用 .toString() 验证内容
        String code = "import nova.io.*\n" +
                "readLines(\"" + esc(file) + "\").toString()";
        Object result = compileAndRun(code);
        assertEquals("[a, b, c]", asString(result));
    }

    // ============ 文件操作 ============

    @Test
    @DisplayName("fileExists — 存在的文件")
    void testFileExistsTrue() throws Exception {
        Path file = tempDir.resolve("exists.txt");
        Files.write(file, "x".getBytes(StandardCharsets.UTF_8));
        String code = "import nova.io.*\n" +
                "fileExists(\"" + esc(file) + "\")";
        Object result = compileAndRun(code);
        assertTrue(asBool(result));
    }

    @Test
    @DisplayName("fileExists — 不存在的文件")
    void testFileExistsFalse() throws Exception {
        Path file = tempDir.resolve("no_such_file.txt");
        String code = "import nova.io.*\n" +
                "fileExists(\"" + esc(file) + "\")";
        Object result = compileAndRun(code);
        assertFalse(asBool(result));
    }

    @Test
    @DisplayName("deleteFile")
    void testDeleteFile() throws Exception {
        Path file = tempDir.resolve("del.txt");
        Files.write(file, "x".getBytes(StandardCharsets.UTF_8));
        String code = "import nova.io.*\n" +
                "deleteFile(\"" + esc(file) + "\")\n" +
                "fileExists(\"" + esc(file) + "\")";
        Object result = compileAndRun(code);
        assertFalse(asBool(result));
    }

    @Test
    @DisplayName("copyFile")
    void testCopyFile() throws Exception {
        Path src = tempDir.resolve("src.txt");
        Path dst = tempDir.resolve("dst.txt");
        Files.write(src, "copy me".getBytes(StandardCharsets.UTF_8));
        String code = "import nova.io.*\n" +
                "copyFile(\"" + esc(src) + "\", \"" + esc(dst) + "\")\n" +
                "readFile(\"" + esc(dst) + "\")";
        Object result = compileAndRun(code);
        assertEquals("copy me", asString(result));
    }

    @Test
    @DisplayName("moveFile")
    void testMoveFile() throws Exception {
        Path src = tempDir.resolve("mv_src.txt");
        Path dst = tempDir.resolve("mv_dst.txt");
        Files.write(src, "move me".getBytes(StandardCharsets.UTF_8));
        String code = "import nova.io.*\n" +
                "moveFile(\"" + esc(src) + "\", \"" + esc(dst) + "\")\n" +
                "readFile(\"" + esc(dst) + "\")";
        Object result = compileAndRun(code);
        assertEquals("move me", asString(result));
        assertFalse(Files.exists(src));
    }

    // ============ 目录操作 ============

    @Test
    @DisplayName("mkdir + isDir")
    void testMkdirAndIsDir() throws Exception {
        Path dir = tempDir.resolve("newdir");
        String code = "import nova.io.*\n" +
                "mkdir(\"" + esc(dir) + "\")\n" +
                "isDir(\"" + esc(dir) + "\")";
        Object result = compileAndRun(code);
        assertTrue(asBool(result));
    }

    @Test
    @DisplayName("mkdirs — 多层目录")
    void testMkdirs() throws Exception {
        Path dir = tempDir.resolve("a/b/c");
        String code = "import nova.io.*\n" +
                "mkdirs(\"" + esc(dir) + "\")\n" +
                "isDir(\"" + esc(dir) + "\")";
        Object result = compileAndRun(code);
        assertTrue(asBool(result));
    }

    @Test
    @DisplayName("listDir")
    void testListDir() throws Exception {
        Files.write(tempDir.resolve("a.txt"), "".getBytes());
        Files.write(tempDir.resolve("b.txt"), "".getBytes());
        // listDir 返回 ArrayList<String>，用 .toString() 验证
        String code = "import nova.io.*\n" +
                "listDir(\"" + esc(tempDir) + "\").toString()";
        Object result = compileAndRun(code);
        String s = asString(result);
        assertTrue(s.contains("a.txt"));
        assertTrue(s.contains("b.txt"));
    }

    // ============ 文件信息 ============

    @Test
    @DisplayName("isFile")
    void testIsFile() throws Exception {
        Path file = tempDir.resolve("check.txt");
        Files.write(file, "x".getBytes());
        String code = "import nova.io.*\n" +
                "isFile(\"" + esc(file) + "\")";
        Object result = compileAndRun(code);
        assertTrue(asBool(result));
    }

    @Test
    @DisplayName("fileSize")
    void testFileSize() throws Exception {
        Path file = tempDir.resolve("size.txt");
        Files.write(file, "12345".getBytes(StandardCharsets.UTF_8));
        String code = "import nova.io.*\n" +
                "fileSize(\"" + esc(file) + "\")";
        Object result = compileAndRun(code);
        assertEquals(5L, ((Number) result).longValue());
    }

    // ============ 路径操作 ============

    @Test
    @DisplayName("pathJoin")
    void testPathJoin() throws Exception {
        String code = "import nova.io.*\n" +
                "pathJoin(\"/usr\", \"local\")";
        Object result = compileAndRun(code);
        String joined = asString(result);
        assertTrue(joined.endsWith("local"));
        assertTrue(joined.contains("usr"));
    }

    @Test
    @DisplayName("fileName")
    void testFileName() throws Exception {
        String code = "import nova.io.*\n" +
                "fileName(\"/path/to/hello.txt\")";
        Object result = compileAndRun(code);
        assertEquals("hello.txt", asString(result));
    }

    @Test
    @DisplayName("fileExtension")
    void testFileExtension() throws Exception {
        String code = "import nova.io.*\n" +
                "fileExtension(\"/path/to/hello.nova\")";
        Object result = compileAndRun(code);
        assertEquals("nova", asString(result));
    }

    @Test
    @DisplayName("parentDir")
    void testParentDir() throws Exception {
        Path file = tempDir.resolve("child.txt");
        String code = "import nova.io.*\n" +
                "parentDir(\"" + esc(file) + "\")";
        Object result = compileAndRun(code);
        assertEquals(tempDir.toString(), asString(result));
    }

    @Test
    @DisplayName("absolutePath")
    void testAbsolutePath() throws Exception {
        String code = "import nova.io.*\n" +
                "absolutePath(\".\")";
        Object result = compileAndRun(code);
        String abs = asString(result);
        assertTrue(Paths.get(abs).isAbsolute());
    }

    @Test
    @DisplayName("currentDir — 无参函数")
    void testCurrentDir() throws Exception {
        String code = "import nova.io.*\n" +
                "currentDir()";
        Object result = compileAndRun(code);
        String dir = asString(result);
        assertTrue(dir.length() > 0);
    }

    @Test
    @DisplayName("tempDir — 无参函数")
    void testTempDir() throws Exception {
        String code = "import nova.io.*\n" +
                "tempDir()";
        Object result = compileAndRun(code);
        String dir = asString(result);
        assertTrue(Files.isDirectory(Paths.get(dir)));
    }

    @Test
    @DisplayName("tempFile — 创建临时文件")
    void testTempFile() throws Exception {
        String code = "import nova.io.*\n" +
                "tempFile()";
        Object result = compileAndRun(code);
        Path tmp = Paths.get(asString(result));
        assertTrue(Files.exists(tmp));
        Files.deleteIfExists(tmp);
    }

    // ============ 综合场景 ============

    @Test
    @DisplayName("综合: 写入 → 读取 → 文件信息 → 删除")
    void testFullWorkflow() throws Exception {
        Path file = tempDir.resolve("workflow.txt");
        String code = "import nova.io.*\n" +
                "val path = \"" + esc(file) + "\"\n" +
                "writeFile(path, \"nova io test\")\n" +
                "val content = readFile(path)\n" +
                "val size = fileSize(path)\n" +
                "val name = fileName(path)\n" +
                "deleteFile(path)\n" +
                "val gone = !fileExists(path)\n" +
                "content + \"|\" + size + \"|\" + name + \"|\" + gone";
        Object result = compileAndRun(code);
        assertEquals("nova io test|12|workflow.txt|true", asString(result));
    }

    /** 转义路径中的反斜杠（Windows） */
    private static String esc(Path p) {
        return p.toString().replace("\\", "\\\\");
    }
}
