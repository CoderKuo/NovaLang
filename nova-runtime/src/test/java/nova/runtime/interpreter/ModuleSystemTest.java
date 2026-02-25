package nova.runtime.interpreter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 模块系统单元测试
 */
@DisplayName("模块系统")
class ModuleSystemTest {

    private Interpreter interpreter;
    private ByteArrayOutputStream outContent;

    @BeforeEach
    void setUp() {
        interpreter = new Interpreter();
        outContent = new ByteArrayOutputStream();
        interpreter.setStdout(new PrintStream(outContent));
    }

    private String getOutput() {
        return outContent.toString().trim().replace("\r\n", "\n");
    }

    private File writeNovaFile(Path dir, String fileName, String content) throws Exception {
        File file = dir.resolve(fileName).toFile();
        file.getParentFile().mkdirs();
        Writer writer = new OutputStreamWriter(new FileOutputStream(file), "UTF-8");
        try {
            writer.write(content);
        } finally {
            writer.close();
        }
        return file;
    }

    // ============ Java 类导入 ============

    @Nested
    @DisplayName("Java 类导入")
    class JavaClassImportTests {

        @Test
        @DisplayName("import java java.util.ArrayList — 基本导入和使用")
        void testImportArrayList() {
            interpreter.eval(
                "import java java.util.ArrayList\n" +
                "val list = ArrayList()\n" +
                "list.add(\"hello\")\n" +
                "list.add(\"world\")\n" +
                "println(list.size())",
                "<test>"
            );
            assertEquals("2", getOutput());
        }

        @Test
        @DisplayName("import java java.util.HashMap — 导入 HashMap")
        void testImportHashMap() {
            interpreter.eval(
                "import java java.util.HashMap\n" +
                "val map = HashMap()\n" +
                "map.put(\"key\", \"value\")\n" +
                "println(map.get(\"key\"))",
                "<test>"
            );
            assertEquals("value", getOutput());
        }

        @Test
        @DisplayName("不存在的 Java 类 → 报错")
        void testImportNonExistentJavaClass() {
            assertThrows(NovaRuntimeException.class, () ->
                interpreter.eval(
                    "import java com.nonexistent.FakeClass",
                    "<test>"
                )
            );
        }

        @Test
        @DisplayName("Java 别名导入: import java ... as JavaList")
        void testJavaAliasImport() {
            interpreter.eval(
                "import java java.util.ArrayList as JavaList\n" +
                "val list = JavaList()\n" +
                "list.add(42)\n" +
                "println(list.size())",
                "<test>"
            );
            assertEquals("1", getOutput());
        }
    }

    // ============ Java 通配符导入 ============

    @Nested
    @DisplayName("Java 通配符导入")
    class JavaWildcardImportTests {

        @Test
        @DisplayName("import java java.util.* — 延迟解析 ArrayList")
        void testWildcardImportArrayList() {
            interpreter.eval(
                "import java java.util.*\n" +
                "val list = ArrayList()\n" +
                "list.add(\"hi\")\n" +
                "println(list.size())",
                "<test>"
            );
            assertEquals("1", getOutput());
        }

        @Test
        @DisplayName("import java java.util.* — 延迟解析 HashMap")
        void testWildcardImportHashMap() {
            interpreter.eval(
                "import java java.util.*\n" +
                "val map = HashMap()\n" +
                "map.put(1, 2)\n" +
                "println(map.size())",
                "<test>"
            );
            assertEquals("1", getOutput());
        }

        @Test
        @DisplayName("通配符导入后使用多个类")
        void testWildcardMultipleClasses() {
            interpreter.eval(
                "import java java.util.*\n" +
                "val list = ArrayList()\n" +
                "val set = HashSet()\n" +
                "list.add(\"a\")\n" +
                "set.add(\"b\")\n" +
                "println(list.size() + set.size())",
                "<test>"
            );
            assertEquals("2", getOutput());
        }
    }

    // ============ Java 静态导入 ============

    @Nested
    @DisplayName("Java 静态导入")
    class JavaStaticImportTests {

        @Test
        @DisplayName("import static java.lang.Short.MAX_VALUE — 导入静态字段")
        void testStaticFieldImport() {
            interpreter.eval(
                "import static java.lang.Short.MAX_VALUE\n" +
                "println(MAX_VALUE)",
                "<test>"
            );
            assertEquals("32767", getOutput());
        }

        @Test
        @DisplayName("import static java.lang.Integer.MAX_VALUE — 导入静态字段")
        void testStaticFieldImportMaxValue() {
            interpreter.eval(
                "import static java.lang.Integer.MAX_VALUE\n" +
                "println(MAX_VALUE)",
                "<test>"
            );
            assertEquals("2147483647", getOutput());
        }
    }

    // ============ Nova 模块导入 ============

    @Nested
    @DisplayName("Nova 模块导入")
    class NovaModuleImportTests {

        @TempDir
        Path tempDir;

        @Test
        @DisplayName("import models.User — 导入类")
        void testImportNovaClass() throws Exception {
            writeNovaFile(tempDir, "models.nova",
                "class User(val name: String, val age: Int) {\n" +
                "    fun greet() = \"Hello, \" + name\n" +
                "}"
            );
            writeNovaFile(tempDir, "app.nova",
                "import models.User\n" +
                "val u = User(\"Alice\", 30)\n" +
                "println(u.greet())"
            );

            interpreter.setScriptBasePath(tempDir);
            String source = new String(java.nio.file.Files.readAllBytes(tempDir.resolve("app.nova")));
            interpreter.eval(source, tempDir.resolve("app.nova").toString());
            assertEquals("Hello, Alice", getOutput());
        }

        @Test
        @DisplayName("import models.* — 通配符导入所有符号")
        void testWildcardNovaImport() throws Exception {
            writeNovaFile(tempDir, "utils.nova",
                "fun double(n: Int) = n * 2\n" +
                "fun triple(n: Int) = n * 3"
            );
            writeNovaFile(tempDir, "app.nova",
                "import utils.*\n" +
                "println(double(5))\n" +
                "println(triple(5))"
            );

            interpreter.setScriptBasePath(tempDir);
            String source = new String(java.nio.file.Files.readAllBytes(tempDir.resolve("app.nova")));
            interpreter.eval(source, tempDir.resolve("app.nova").toString());
            assertEquals("10\n15", getOutput());
        }

        @Test
        @DisplayName("import models.User as U — 别名导入")
        void testNovaAliasImport() throws Exception {
            writeNovaFile(tempDir, "models.nova",
                "class User(val name: String)"
            );
            writeNovaFile(tempDir, "app.nova",
                "import models.User as U\n" +
                "val u = U(\"Bob\")\n" +
                "println(u.name)"
            );

            interpreter.setScriptBasePath(tempDir);
            String source = new String(java.nio.file.Files.readAllBytes(tempDir.resolve("app.nova")));
            interpreter.eval(source, tempDir.resolve("app.nova").toString());
            assertEquals("Bob", getOutput());
        }

        @Test
        @DisplayName("深层模块路径: import utils.math.Vector")
        void testDeepModuleImport() throws Exception {
            writeNovaFile(tempDir, "utils" + File.separator + "math.nova",
                "class Vector(val x: Int, val y: Int) {\n" +
                "    fun sum() = x + y\n" +
                "}"
            );
            writeNovaFile(tempDir, "app.nova",
                "import utils.math.Vector\n" +
                "val v = Vector(3, 4)\n" +
                "println(v.sum())"
            );

            interpreter.setScriptBasePath(tempDir);
            String source = new String(java.nio.file.Files.readAllBytes(tempDir.resolve("app.nova")));
            interpreter.eval(source, tempDir.resolve("app.nova").toString());
            assertEquals("7", getOutput());
        }

        @Test
        @DisplayName("不存在的模块 → 报错")
        void testImportNonExistentModule() throws Exception {
            interpreter.setScriptBasePath(tempDir);
            assertThrows(NovaRuntimeException.class, () ->
                interpreter.eval("import nonexistent.Foo", "<test>")
            );
        }

        @Test
        @DisplayName("模块中不存在的符号 → 报错")
        void testImportNonExistentSymbol() throws Exception {
            writeNovaFile(tempDir, "models.nova", "class User(val name: String)");
            interpreter.setScriptBasePath(tempDir);
            assertThrows(NovaRuntimeException.class, () ->
                interpreter.eval("import models.NonExistent", "<test>")
            );
        }

        @Test
        @DisplayName("模块缓存 — 同一模块只加载一次")
        void testModuleCaching() throws Exception {
            writeNovaFile(tempDir, "counter.nova",
                "var count = 0\n" +
                "fun increment() { count = count + 1 }\n" +
                "fun getCount() = count"
            );
            writeNovaFile(tempDir, "app.nova",
                "import counter.*\n" +
                "increment()\n" +
                "increment()\n" +
                "println(getCount())"
            );

            interpreter.setScriptBasePath(tempDir);
            String source = new String(java.nio.file.Files.readAllBytes(tempDir.resolve("app.nova")));
            interpreter.eval(source, tempDir.resolve("app.nova").toString());
            assertEquals("2", getOutput());
        }

        @Test
        @DisplayName("链式依赖 — app import a, a import b")
        void testChainedDependency() throws Exception {
            writeNovaFile(tempDir, "b.nova",
                "fun greetB() = \"Hello from B\""
            );
            writeNovaFile(tempDir, "a.nova",
                "import b.greetB\n" +
                "fun greetA() = \"Hello from A\"\n" +
                "fun callB() = greetB()"
            );
            writeNovaFile(tempDir, "app.nova",
                "import a.*\n" +
                "println(greetA())\n" +
                "println(callB())"
            );

            interpreter.setScriptBasePath(tempDir);
            String source = new String(java.nio.file.Files.readAllBytes(tempDir.resolve("app.nova")));
            interpreter.eval(source, tempDir.resolve("app.nova").toString());
            assertEquals("Hello from A\nHello from B", getOutput());
        }

        @Test
        @DisplayName("循环依赖 — 先定义再 import（Python 式）")
        void testCircularDependency() throws Exception {
            // A 先定义 greetA，再 import B；B 先定义 greetB，再 import A
            // 按源码顺序执行，循环引用时对方的符号已注册
            writeNovaFile(tempDir, "a.nova",
                "fun greetA() = \"Hello from A\"\n" +
                "import b.greetB\n" +
                "fun callB() = greetB()"
            );
            writeNovaFile(tempDir, "b.nova",
                "fun greetB() = \"Hello from B\"\n" +
                "import a.greetA\n" +
                "fun callA() = greetA()"
            );
            writeNovaFile(tempDir, "app.nova",
                "import a.*\n" +
                "println(greetA())\n" +
                "println(callB())"
            );

            interpreter.setScriptBasePath(tempDir);
            String source = new String(java.nio.file.Files.readAllBytes(tempDir.resolve("app.nova")));
            interpreter.eval(source, tempDir.resolve("app.nova").toString());
            assertEquals("Hello from A\nHello from B", getOutput());
        }
    }

    // ============ 混合导入 ============

    @Nested
    @DisplayName("混合导入")
    class MixedImportTests {

        @TempDir
        Path tempDir;

        @Test
        @DisplayName("同时使用 Java 导入和 Nova 模块导入")
        void testMixedImports() throws Exception {
            writeNovaFile(tempDir, "models.nova",
                "class User(val name: String)"
            );
            writeNovaFile(tempDir, "app.nova",
                "import java java.util.ArrayList\n" +
                "import models.User\n" +
                "val users = ArrayList()\n" +
                "users.add(User(\"Alice\"))\n" +
                "users.add(User(\"Bob\"))\n" +
                "println(users.size())"
            );

            interpreter.setScriptBasePath(tempDir);
            String source = new String(java.nio.file.Files.readAllBytes(tempDir.resolve("app.nova")));
            interpreter.eval(source, tempDir.resolve("app.nova").toString());
            assertEquals("2", getOutput());
        }
    }

    // ============ REPL 模式导入 ============

    @Nested
    @DisplayName("REPL 模式导入")
    class ReplImportTests {

        @Test
        @DisplayName("REPL 中 import java java.util.ArrayList")
        void testReplJavaImport() {
            interpreter.setReplMode(true);
            interpreter.evalRepl("import java java.util.ArrayList");
            interpreter.evalRepl("val list = ArrayList()");
            interpreter.evalRepl("list.add(\"test\")");
            interpreter.evalRepl("println(list.size())");
            assertEquals("1", getOutput());
        }

        @Test
        @DisplayName("REPL 中 import static")
        void testReplStaticImport() {
            interpreter.setReplMode(true);
            interpreter.evalRepl("import static java.lang.Byte.MIN_VALUE");
            interpreter.evalRepl("println(MIN_VALUE)");
            assertEquals("-128", getOutput());
        }
    }

    // ============ ModuleLoader 单元测试 ============

    @Nested
    @DisplayName("ModuleLoader")
    class ModuleLoaderTests {

        @TempDir
        Path tempDir;

        @Test
        @DisplayName("resolveModulePath — 存在的模块")
        void testResolveExistingModule() throws Exception {
            writeNovaFile(tempDir, "models.nova", "class User");
            ModuleLoader loader = new ModuleLoader(tempDir);

            Path path = loader.resolveModulePath(java.util.Arrays.asList("models"));
            assertNotNull(path);
            assertTrue(path.toString().endsWith("models.nova"));
        }

        @Test
        @DisplayName("resolveModulePath — 不存在的模块返回 null")
        void testResolveNonExistentModule() {
            ModuleLoader loader = new ModuleLoader(tempDir);
            Path path = loader.resolveModulePath(java.util.Arrays.asList("nonexistent"));
            assertNull(path);
        }

        @Test
        @DisplayName("resolveModulePath — 子目录模块")
        void testResolveSubdirModule() throws Exception {
            writeNovaFile(tempDir, "utils" + File.separator + "math.nova", "class Vec2");
            ModuleLoader loader = new ModuleLoader(tempDir);

            Path path = loader.resolveModulePath(java.util.Arrays.asList("utils", "math"));
            assertNotNull(path);
            assertTrue(path.toString().endsWith("math.nova"));
        }
    }
}
