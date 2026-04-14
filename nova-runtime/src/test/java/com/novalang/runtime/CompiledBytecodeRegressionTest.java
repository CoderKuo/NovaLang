package com.novalang.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayName("Compiled bytecode regression coverage")
class CompiledBytecodeRegressionTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("compilation cache should not reuse bytecode for different sources with colliding hash/length")
    void compilationCacheShouldUseContentStableKeys() {
        Nova nova = new Nova().enableCompilationCache();

        Object first = nova.compileToBytecode("val x = \"FB\"\nx", "shared.nova").run();
        Object second = nova.compileToBytecode("val x = \"Ea\"\nx", "shared.nova").run();

        assertEquals("FB", first);
        assertEquals("Ea", second);
    }

    @Test
    @DisplayName("CompiledNova.call should honor the configured script class loader")
    void compiledCallShouldHonorScriptClassLoader() throws Exception {
        try (URLClassLoader loader = compileScriptOnlyClassLoader()) {
            CompiledNova compiled = new Nova().compileToBytecode(
                    "fun loadAndPing() = javaClass(\"dynamic.ScriptOnlyClass\").ping()",
                    "loader-call.nova");
            compiled.setScriptClassLoader(loader);

            assertEquals("pong", compiled.call("loadAndPing"));
        }
    }

    @Test
    @DisplayName("CompiledNova.callDirect should honor the configured script class loader")
    void compiledCallDirectShouldHonorScriptClassLoader() throws Exception {
        try (URLClassLoader loader = compileScriptOnlyClassLoader()) {
            CompiledNova compiled = new Nova().compileToBytecode(
                    "fun loadAndPing() = javaClass(\"dynamic.ScriptOnlyClass\").ping()",
                    "loader-call-direct.nova");
            compiled.setScriptClassLoader(loader);

            assertEquals("pong", compiled.callDirect("loadAndPing", new HashMap<String, Object>()));
        }
    }

    @Test
    @DisplayName("CompiledNova.registerExtension should support Function4 receiver extensions")
    void compiledRegisterExtensionShouldSupportFunction4() {
        CompiledNova compiled = new Nova().compileToBytecode(
                "\"core\".surround3(\"<\", \"|\", \">\")",
                "ext-f4.nova");
        compiled.registerExtension(String.class, "surround3",
                (Object self, Object a, Object b, Object c) -> String.valueOf(a) + self + b + c);

        assertEquals("<core|>", compiled.run());
    }

    @Test
    @DisplayName("CompiledNova.registerExtension should support Function5 receiver extensions")
    void compiledRegisterExtensionShouldSupportFunction5() {
        CompiledNova compiled = new Nova().compileToBytecode(
                "\"core\".surround4(\"(\", \"<\", \">\", \")\")",
                "ext-f5.nova");
        compiled.registerExtension(String.class, "surround4",
                (Object self, Object a, Object b, Object c, Object d) ->
                        String.valueOf(a) + b + self + c + d);

        assertEquals("(<core>)", compiled.run());
    }

    @Test
    @DisplayName("CompiledNova.registerExtension should support Function6 receiver extensions")
    void compiledRegisterExtensionShouldSupportFunction6() {
        CompiledNova compiled = new Nova().compileToBytecode(
                "\"core\".surround5(\"a\", \"b\", \"c\", \"d\", \"e\")",
                "ext-f6.nova");
        compiled.registerExtension(String.class, "surround5",
                (Object self, Object a, Object b, Object c, Object d, Object e) ->
                        String.valueOf(a) + b + self + c + d + e);

        assertEquals("abcorecde", compiled.run());
    }

    @Test
    @DisplayName("compileToBytecodeStatic should still allow registering extensions after compilation")
    void staticCompiledNovaShouldAcceptExtensions() {
        CompiledNova compiled = Nova.compileToBytecodeStatic("\"x\".shout()", "static-ext.nova");
        compiled.registerExtension(String.class, "shout", (Object s) -> ((String) s).toUpperCase());

        assertEquals("X", compiled.run());
    }

    @Test
    @DisplayName("available functions should only expose top-level script functions, not generated helpers")
    void availableFunctionsShouldHideGeneratedHelpers() {
        CompiledNova compiled = new Nova().compileToBytecode(
                "@builder class Box(var x: Int)\n" +
                "fun greet() = \"hi\"",
                "available-functions.nova");

        assertEquals(true, compiled.hasFunction("greet"));
        assertEquals(false, compiled.hasFunction("builder"));
        assertEquals(java.util.Collections.singleton("greet"), compiled.getAvailableFunctions());
    }

    private URLClassLoader compileScriptOnlyClassLoader() throws Exception {
        Path classesDir = tempDir.resolve("classes");
        Path srcDir = tempDir.resolve("src").resolve("dynamic");
        Files.createDirectories(srcDir);
        Files.createDirectories(classesDir);

        Path javaFile = srcDir.resolve("ScriptOnlyClass.java");
        Files.write(javaFile,
                ("package dynamic;\n" +
                        "public final class ScriptOnlyClass {\n" +
                        "    public static String ping() { return \"pong\"; }\n" +
                        "}\n").getBytes(StandardCharsets.UTF_8));

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Tests require a JDK compiler");
        int exit = compiler.run(null, null, null,
                "-encoding", "UTF-8",
                "-d", classesDir.toString(),
                javaFile.toString());
        assertEquals(0, exit, "helper class should compile successfully");

        return new URLClassLoader(new URL[]{classesDir.toUri().toURL()}, null);
    }
}
