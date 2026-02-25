package nova.script;

import nova.runtime.CompiledNova;

import javax.script.Bindings;
import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import java.util.Map;

/**
 * NovaLang 预编译脚本 — 委托 {@link CompiledNova} 的字节码模式。
 */
public class NovaCompiledScript extends CompiledScript {

    private final NovaScriptEngine engine;
    private final CompiledNova compiled;

    NovaCompiledScript(NovaScriptEngine engine, CompiledNova compiled) {
        this.engine = engine;
        this.compiled = compiled;
    }

    @Override
    public Object eval(ScriptContext context) throws ScriptException {
        // 注入 Bindings
        Bindings bindings = context.getBindings(ScriptContext.ENGINE_SCOPE);
        if (bindings != null) {
            for (Map.Entry<String, Object> entry : bindings.entrySet()) {
                compiled.set(entry.getKey(), entry.getValue());
            }
        }
        try {
            Object result = compiled.run();
            // 导出变量回 Bindings
            if (bindings != null) {
                bindings.putAll(compiled.getBindings());
            }
            return result;
        } catch (Exception e) {
            throw new ScriptException(e);
        }
    }

    @Override
    public ScriptEngine getEngine() {
        return engine;
    }
}
