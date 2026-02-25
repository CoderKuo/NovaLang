package com.novalang.ir;

import com.novalang.compiler.ast.decl.Program;
import com.novalang.compiler.lexer.Lexer;
import com.novalang.compiler.parser.Parser;
import com.novalang.ir.pass.PassPipeline;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import com.novalang.compiler.compiler.NovaCompilerApi;

/**
 * 基于 IR（HIR + MIR）的编译器门面。
 * 管线：源码 → Lexer → Parser → AST → HIR → MIR → JVM 字节码。
 */
public class NovaIrCompiler implements NovaCompilerApi {

    private PrintStream out = System.out;
    private final PassPipeline pipeline;

    public NovaIrCompiler() {
        this.pipeline = PassPipeline.createDefault();
    }

    public NovaIrCompiler(PassPipeline pipeline) {
        this.pipeline = pipeline;
    }

    public void setOut(PrintStream out) {
        this.out = out;
    }

    public PassPipeline getPipeline() {
        return pipeline;
    }

    /**
     * 启用脚本模式：未解析变量通过 NovaScriptContext 读取，main() 返回 Object。
     */
    public void setScriptMode(boolean scriptMode) {
        pipeline.setScriptMode(scriptMode);
    }

    /**
     * 编译源代码。
     *
     * @param source   源代码
     * @param fileName 文件名
     * @return className → bytecode 映射
     */
    public Map<String, byte[]> compile(String source, String fileName) {
        Lexer lexer = new Lexer(source, fileName);
        Parser parser = new Parser(lexer, fileName);
        Program program = parser.parse();
        return pipeline.execute(program);
    }

    /**
     * 编译文件。
     */
    public Map<String, byte[]> compileFile(File file) throws IOException {
        String source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        return compile(source, file.getName());
    }

    /**
     * 编译并保存到目录。
     */
    public void compileAndSave(String source, String fileName, File outDir) throws IOException {
        Map<String, byte[]> classes = compile(source, fileName);

        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            String className = entry.getKey();
            byte[] bytecode = entry.getValue();

            String path = className.replace('/', File.separatorChar) + ".class";
            File classFile = new File(outDir, path);
            classFile.getParentFile().mkdirs();

            try (FileOutputStream fos = new FileOutputStream(classFile)) {
                fos.write(bytecode);
            }

            out.println("Generated: " + classFile.getPath());
        }
    }

    /**
     * 编译并加载类。
     *
     * @param source   源代码
     * @param fileName 文件名
     * @return 加载的类（类名 → Class 对象）
     */
    public Map<String, Class<?>> compileAndLoad(String source, String fileName) {
        Map<String, byte[]> classes = compile(source, fileName);
        NovaClassLoader loader = new NovaClassLoader(classes);

        Map<String, Class<?>> loadedClasses = new HashMap<>();
        for (String className : classes.keySet()) {
            try {
                loadedClasses.put(className, loader.loadClass(className));
            } catch (ClassNotFoundException e) {
                throw new RuntimeException("Failed to load class: " + className, e);
            }
        }
        return loadedClasses;
    }

    private static class NovaClassLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        public NovaClassLoader(Map<String, byte[]> classes) {
            super(NovaClassLoader.class.getClassLoader());
            this.classes = classes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytecode = classes.get(name);
            if (bytecode != null) {
                return defineClass(name, bytecode, 0, bytecode.length);
            }
            throw new ClassNotFoundException(name);
        }
    }
}
