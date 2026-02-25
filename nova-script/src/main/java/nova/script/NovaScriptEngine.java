package nova.script;

import nova.runtime.CompiledNova;
import nova.runtime.Nova;

import java.nio.file.Path;

import javax.script.*;
import java.io.*;
import java.util.Map;

/**
 * NovaLang 的 JSR-223 ScriptEngine 实现。
 *
 * <p>委托 {@link Nova} 实现核心功能：
 * <ul>
 *   <li>{@code eval()} — 解释器执行，支持完整 Bindings 交互</li>
 *   <li>{@code compile()} — 编译为 JVM 字节码，eval 时直接执行字节码</li>
 * </ul>
 */
public class NovaScriptEngine extends AbstractScriptEngine implements Compilable {

    private final NovaScriptEngineFactory factory;
    private final Nova nova;

    public NovaScriptEngine(NovaScriptEngineFactory factory) {
        this.factory = factory;
        this.nova = new Nova();
    }

    @Override
    public Object eval(String script, ScriptContext context) throws ScriptException {
        try {
            injectBindings(context);
            redirectIO(context);

            Object result = nova.eval(script);

            exportBindings(context);
            return result;
        } catch (Exception e) {
            throw new ScriptException(e);
        }
    }

    @Override
    public Object eval(Reader reader, ScriptContext context) throws ScriptException {
        return eval(readAll(reader), context);
    }

    @Override
    public Bindings createBindings() {
        return new SimpleBindings();
    }

    @Override
    public ScriptEngineFactory getFactory() {
        return factory;
    }

    // ---- Compilable ----

    /**
     * 预编译脚本为 JVM 字节码。
     */
    @Override
    public CompiledScript compile(String script) throws ScriptException {
        try {
            CompiledNova compiled = nova.compileToBytecode(script, "<script>");
            return new NovaCompiledScript(this, compiled);
        } catch (Exception e) {
            throw new ScriptException(e);
        }
    }

    @Override
    public CompiledScript compile(Reader reader) throws ScriptException {
        return compile(readAll(reader));
    }

    /**
     * 编译文件源码为字节码
     */
    public CompiledScript compileFile(String source, String fileName) throws ScriptException {
        try {
            CompiledNova compiled = nova.compileToBytecode(source, fileName);
            return new NovaCompiledScript(this, compiled);
        } catch (Exception e) {
            throw new ScriptException(e);
        }
    }

    /**
     * 设置脚本基路径（用于模块导入）
     */
    public void setScriptBasePath(Path basePath) {
        nova.getInterpreter().setScriptBasePath(basePath);
    }

    Nova getNova() {
        return nova;
    }

    // ---- 内部方法 ----

    private void injectBindings(ScriptContext context) {
        Bindings bindings = context.getBindings(ScriptContext.ENGINE_SCOPE);
        if (bindings == null) return;
        for (Map.Entry<String, Object> entry : bindings.entrySet()) {
            nova.set(entry.getKey(), entry.getValue());
        }
    }

    private void exportBindings(ScriptContext context) {
        Bindings bindings = context.getBindings(ScriptContext.ENGINE_SCOPE);
        if (bindings == null) return;
        bindings.putAll(nova.getAll());
    }

    private void redirectIO(ScriptContext context) {
        Writer writer = context.getWriter();
        if (writer != null) {
            nova.setStdout(new PrintStream(new WriterOutputStream(writer), true, java.nio.charset.StandardCharsets.UTF_8));
        }
        Writer errorWriter = context.getErrorWriter();
        if (errorWriter != null) {
            nova.setStderr(new PrintStream(new WriterOutputStream(errorWriter), true, java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static String readAll(Reader reader) throws ScriptException {
        try {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        } catch (IOException e) {
            throw new ScriptException(e);
        }
    }

    /**
     * 将 Writer 适配为 OutputStream（用于 PrintStream 包装）
     * 使用 UTF-8 编码，正确处理多字节字符。
     */
    static class WriterOutputStream extends OutputStream {
        private final Writer writer;
        private final byte[] singleByte = new byte[1];

        WriterOutputStream(Writer writer) {
            this.writer = writer;
        }

        @Override
        public void write(int b) throws IOException {
            // 缓冲单字节，处理 UTF-8 多字节序列
            singleByte[0] = (byte) b;
            write(singleByte, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            // 使用 UTF-8 解码为字符后写入 Writer
            writer.write(new String(b, off, len, java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override
        public void flush() throws IOException {
            writer.flush();
        }

        @Override
        public void close() throws IOException {
            // 不关闭外部 Writer，仅 flush — 生命周期由宿主管理
            writer.flush();
        }
    }
}
