package nova.runtime.interpreter;

import nova.runtime.AbstractNovaValue;
import nova.runtime.NovaValue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 轻量命名空间容器 — 持有一组命名的函数和值，
 * 供 Nova 脚本通过 {@code lib.func(args)} / {@code lib.VAL} 访问。
 *
 * <pre>
 * // Java 侧
 * nova.defineLibrary("http", lib -> {
 *     lib.defineFunction("get", url -> httpGet((String) url));
 *     lib.defineVal("TIMEOUT", 30000);
 * });
 *
 * // Nova 脚本
 * http.get("https://api.example.com")
 * println(http.TIMEOUT)
 * </pre>
 */
public final class NovaLibrary extends AbstractNovaValue {

    private final String name;
    private final Map<String, NovaValue> members = new LinkedHashMap<>();

    public NovaLibrary(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    /** 注册或覆盖一个成员 */
    public void putMember(String memberName, NovaValue value) {
        members.put(memberName, value);
    }

    /** 查找成员，不存在返回 null */
    public NovaValue getMember(String memberName) {
        return members.get(memberName);
    }

    /** 是否包含指定成员 */
    public boolean hasMember(String memberName) {
        return members.containsKey(memberName);
    }

    /** 获取所有成员（只读视图） */
    public Map<String, NovaValue> getMembers() {
        return Collections.unmodifiableMap(members);
    }

    @Override
    public String getTypeName() {
        return "Library";
    }

    @Override
    public Object toJavaValue() {
        return this;
    }

    @Override
    public String toString() {
        return "<library " + name + ">";
    }
}
