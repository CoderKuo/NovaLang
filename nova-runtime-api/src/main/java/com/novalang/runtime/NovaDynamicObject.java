package com.novalang.runtime;

/**
 * 动态属性对象接口。
 *
 * <p>Java 对象实现此接口后，Nova 脚本中的点号属性访问和赋值将委托给此接口，
 * 而非走 Java 反射。适用于运行时动态属性的场景（如配置对象、数据容器等）。</p>
 *
 * <pre>
 * public class PlayerData implements NovaDynamicObject {
 *     private final Map&lt;String, Object&gt; data = new HashMap&lt;&gt;();
 *
 *     public Object getMember(String name) { return data.get(name); }
 *     public void setMember(String name, Object value) { data.put(name, value); }
 *     public boolean hasMember(String name) { return data.containsKey(name); }
 * }
 *
 * // Nova 脚本中：
 * playerData.hp = 100
 * println(playerData.hp)  // 100
 * </pre>
 */
public interface NovaDynamicObject {

    /** 读取属性值，不存在返回 null */
    Object getMember(String name);

    /** 设置属性值 */
    void setMember(String name, Object value);

    /** 检查属性是否存在（默认返回 true） */
    default boolean hasMember(String name) { return true; }
}
