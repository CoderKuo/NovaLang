package nova.runtime.interpreter;
import nova.runtime.*;

import java.util.HashMap;
import java.util.Map;

/**
 * @builder 注解生成的 Builder 对象
 */
public final class NovaBuilder extends NovaValue {

    private final NovaClass targetClass;
    private final Map<String, NovaValue> fields;

    public NovaBuilder(NovaClass targetClass) {
        this.targetClass = targetClass;
        this.fields = new HashMap<String, NovaValue>();
    }

    public NovaClass getTargetClass() {
        return targetClass;
    }

    public Map<String, NovaValue> getFields() {
        return fields;
    }

    public void setField(String name, NovaValue value) {
        fields.put(name, value);
    }

    public NovaValue getField(String name) {
        return fields.get(name);
    }

    @Override
    public String getTypeName() {
        return targetClass.getName() + ".Builder";
    }

    @Override
    public Object toJavaValue() {
        return this;
    }

    @Override
    public String toString() {
        return targetClass.getName() + ".Builder(" + fields + ")";
    }
}
