package nova.script;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * NovaLang 的 JSR-223 ScriptEngineFactory 实现。
 *
 * <p>通过 SPI 机制（META-INF/services）被 {@link javax.script.ScriptEngineManager} 自动发现。</p>
 */
public class NovaScriptEngineFactory implements ScriptEngineFactory {

    private static final String ENGINE_NAME = "NovaLang";
    private static final String ENGINE_VERSION = "0.1.0";
    private static final String LANGUAGE_NAME = "nova";
    private static final String LANGUAGE_VERSION = "0.1.0";

    @Override
    public String getEngineName() {
        return ENGINE_NAME;
    }

    @Override
    public String getEngineVersion() {
        return ENGINE_VERSION;
    }

    @Override
    public List<String> getExtensions() {
        return Collections.singletonList("nova");
    }

    @Override
    public List<String> getMimeTypes() {
        return Collections.singletonList("application/x-nova");
    }

    @Override
    public List<String> getNames() {
        return Arrays.asList("nova", "novalang", "NovaLang");
    }

    @Override
    public String getLanguageName() {
        return LANGUAGE_NAME;
    }

    @Override
    public String getLanguageVersion() {
        return LANGUAGE_VERSION;
    }

    @Override
    public Object getParameter(String key) {
        switch (key) {
            case ScriptEngine.ENGINE:           return getEngineName();
            case ScriptEngine.ENGINE_VERSION:    return getEngineVersion();
            case ScriptEngine.LANGUAGE:          return getLanguageName();
            case ScriptEngine.LANGUAGE_VERSION:  return getLanguageVersion();
            case ScriptEngine.NAME:              return getNames().get(0);
            default:                             return null;
        }
    }

    @Override
    public String getMethodCallSyntax(String obj, String method, String... args) {
        StringBuilder sb = new StringBuilder();
        sb.append(obj).append('.').append(method).append('(');
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(args[i]);
        }
        sb.append(')');
        return sb.toString();
    }

    @Override
    public String getOutputStatement(String toDisplay) {
        return "println(" + toDisplay + ")";
    }

    @Override
    public String getProgram(String... statements) {
        StringBuilder sb = new StringBuilder();
        for (String stmt : statements) {
            sb.append(stmt).append('\n');
        }
        return sb.toString();
    }

    @Override
    public NovaScriptEngine getScriptEngine() {
        return new NovaScriptEngine(this);
    }
}
