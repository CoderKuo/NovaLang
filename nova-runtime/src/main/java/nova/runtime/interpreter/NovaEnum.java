package nova.runtime.interpreter;
import nova.runtime.*;
import nova.runtime.types.Environment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 枚举类
 *
 * <p>表示 Nova 枚举类型。</p>
 *
 * <p>示例：</p>
 * <pre>
 * enum class Status {
 *     PENDING,
 *     RUNNING,
 *     COMPLETED
 * }
 *
 * // 带属性的枚举
 * enum class Color(val rgb: Int) {
 *     RED(0xFF0000),
 *     GREEN(0x00FF00),
 *     BLUE(0x0000FF);
 *
 *     fun isDark() = rgb and 0x808080 == 0
 * }
 * </pre>
 */
public final class NovaEnum extends AbstractNovaValue implements nova.runtime.NovaCallable {

    private final String name;
    private final Map<String, NovaEnumEntry> entries;  // 保持插入顺序
    private final Map<String, nova.runtime.NovaCallable> methods;   // 枚举类方法
    private final Environment closure;

    public NovaEnum(String name, Environment closure) {
        this.name = name;
        this.entries = new LinkedHashMap<String, NovaEnumEntry>();
        this.methods = new LinkedHashMap<String, nova.runtime.NovaCallable>();
        this.closure = closure;
    }

    /** HIR 使用的简化构造器 */
    public NovaEnum(String name) {
        this(name, null);
    }

    /**
     * 获取枚举名称
     */
    public String getName() {
        return name;
    }

    /**
     * 获取闭包环境
     */
    public Environment getClosure() {
        return closure;
    }

    /**
     * 添加枚举条目
     */
    public void addEntry(NovaEnumEntry entry) {
        entries.put(entry.name(), entry);
    }

    /**
     * 获取枚举条目
     */
    public NovaEnumEntry getEntry(String entryName) {
        return entries.get(entryName);
    }

    /**
     * 检查是否有条目
     */
    public boolean hasEntry(String entryName) {
        return entries.containsKey(entryName);
    }

    /**
     * 获取所有枚举值（按声明顺序）
     */
    public List<NovaEnumEntry> values() {
        return new ArrayList<NovaEnumEntry>(entries.values());
    }

    /**
     * 根据名称获取枚举值
     */
    public NovaEnumEntry valueOf(String name) {
        NovaEnumEntry entry = entries.get(name);
        if (entry == null) {
            throw new NovaRuntimeException("No enum constant " + this.name + "." + name);
        }
        return entry;
    }

    /**
     * 添加方法
     */
    public void addMethod(String methodName, nova.runtime.NovaCallable method) {
        methods.put(methodName, method);
    }

    /**
     * 获取方法
     */
    public nova.runtime.NovaCallable getMethod(String methodName) {
        return methods.get(methodName);
    }

    /**
     * 获取枚举条目数量
     */
    public int size() {
        return entries.size();
    }

    @Override
    public String getTypeName() {
        return "Enum:" + name;
    }

    @Override
    public Object toJavaValue() {
        return this;
    }

    @Override
    public String toString() {
        return "enum class " + name;
    }

    // NovaCallable 实现 - 用于 Status.PENDING 这样的静态访问
    @Override
    public int getArity() {
        return -1;
    }

    @Override
    public NovaValue call(ExecutionContext ctx, List<NovaValue> args) {
        // 枚举类不能直接调用
        throw new NovaRuntimeException("Cannot instantiate enum class: " + name);
    }

    /**
     * 创建 values() 方法
     */
    public NovaNativeFunction createValuesMethod() {
        return NovaNativeFunction.create("values", () -> {
            NovaList list = new NovaList();
            for (NovaEnumEntry entry : entries.values()) {
                list.add(entry);
            }
            return list;
        });
    }

    /**
     * 创建 valueOf(name) 方法
     */
    public NovaNativeFunction createValueOfMethod() {
        return NovaNativeFunction.create("valueOf", (nameArg) -> {
            String entryName = nameArg.asString();
            return valueOf(entryName);
        });
    }
}
