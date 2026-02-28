package nova.runtime.interpreter.reflect;
import nova.runtime.*;


import java.util.Map;

/**
 * 注解反射信息
 */
public final class NovaAnnotationInfo extends AbstractNovaValue {

    public final String name;

    // 解释器模式
    private final Map<String, NovaValue> args;
    // 编译模式
    private final Map<String, Object> javaArgs;

    public NovaAnnotationInfo(String name, Map<String, NovaValue> args) {
        this.name = name;
        this.args = args;
        this.javaArgs = null;
    }

    public NovaAnnotationInfo(String name, Map<String, Object> javaArgs, boolean isJavaMode) {
        this.name = name;
        this.args = null;
        this.javaArgs = javaArgs;
    }

    public Map<String, NovaValue> getArgs() {
        return args;
    }

    public Map<String, Object> getJavaArgs() {
        return javaArgs;
    }

    @Override
    public String getTypeName() {
        return "AnnotationInfo";
    }

    @Override
    public Object toJavaValue() {
        return this;
    }

    @Override
    public String toString() {
        return "@" + name;
    }
}
