package nova.runtime.interpreter;
import nova.runtime.*;

import java.util.List;
import java.util.Map;

/**
 * 枚举条目
 *
 * <p>表示枚举类中的一个枚举值。</p>
 *
 * <p>示例：</p>
 * <pre>
 * enum class Status {
 *     PENDING,   // NovaEnumEntry("PENDING", 0)
 *     RUNNING,   // NovaEnumEntry("RUNNING", 1)
 *     COMPLETED  // NovaEnumEntry("COMPLETED", 2)
 * }
 * </pre>
 */
public final class NovaEnumEntry extends NovaValue implements NovaCallable {

    private final String name;
    private final int ordinal;
    private final NovaEnum enumClass;
    private final Map<String, NovaValue> fields;
    private final Map<String, NovaCallable> methods;

    public NovaEnumEntry(String name, int ordinal, NovaEnum enumClass,
                         Map<String, NovaValue> fields, Map<String, NovaCallable> methods) {
        this.name = name;
        this.ordinal = ordinal;
        this.enumClass = enumClass;
        this.fields = fields;
        this.methods = methods;
    }

    /**
     * 获取枚举条目名称
     */
    public String name() {
        return name;
    }

    /**
     * 获取枚举序号（从 0 开始）
     */
    public int ordinal() {
        return ordinal;
    }

    /**
     * 获取所属枚举类
     */
    public NovaEnum getEnumClass() {
        return enumClass;
    }

    /**
     * 获取字段值
     */
    public NovaValue getField(String fieldName) {
        if ("name".equals(fieldName)) {
            return NovaString.of(name);
        }
        if ("ordinal".equals(fieldName)) {
            return NovaInt.of(ordinal);
        }
        NovaValue value = fields.get(fieldName);
        if (value != null) {
            return value;
        }
        throw new NovaRuntimeException("Unknown field: " + fieldName + " on enum " + enumClass.getName() + "." + name);
    }

    /**
     * 检查是否有字段
     */
    public boolean hasField(String fieldName) {
        return "name".equals(fieldName) || "ordinal".equals(fieldName) || fields.containsKey(fieldName);
    }

    /**
     * 获取方法
     */
    public NovaCallable getMethod(String methodName) {
        return methods.get(methodName);
    }

    /**
     * 获取绑定方法
     */
    public NovaBoundMethod getBoundMethod(String methodName) {
        NovaCallable method = methods.get(methodName);
        if (method != null) {
            return new NovaBoundMethod(this, method);
        }
        return null;
    }

    @Override
    public String getTypeName() {
        return enumClass.getName();
    }

    @Override
    public Object toJavaValue() {
        return this;
    }

    @Override
    public String toString() {
        return name;
    }

    @Override
    public boolean equals(NovaValue other) {
        if (other instanceof NovaEnumEntry) {
            NovaEnumEntry otherEntry = (NovaEnumEntry) other;
            return this.enumClass == otherEntry.enumClass && this.ordinal == otherEntry.ordinal;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return name.hashCode() + ordinal * 31;
    }

    // NovaCallable 实现（用于带参数的枚举条目访问）
    @Override
    public String getName() {
        return name;
    }

    @Override
    public int getArity() {
        return 0;
    }

    @Override
    public NovaValue call(Interpreter interpreter, List<NovaValue> args) {
        // 枚举条目直接返回自身
        return this;
    }
}
