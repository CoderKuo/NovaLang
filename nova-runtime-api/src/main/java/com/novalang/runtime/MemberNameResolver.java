package com.novalang.runtime;

/**
 * 自定义成员名称解析器。
 *
 * <p>当 Nova 在 Java 对象上找不到指定成员（字段/方法）时，回调此接口进行名称映射。
 * 典型用途：Minecraft MCP/Mojang 混淆映射（将脚本中的可读名称映射为混淆后的实际名称）。</p>
 *
 * <pre>
 * Nova nova = new Nova();
 * nova.setMemberResolver((target, name, isMethod) -> {
 *     if (isMethod) return McpMappingResolver.resolveMethod(name);
 *     else return McpMappingResolver.resolveField(name);
 * });
 * </pre>
 */
@FunctionalInterface
public interface MemberNameResolver {

    /**
     * 尝试将成员名称解析为实际 Java 名称。
     *
     * @param targetClass 目标对象的 Java 类
     * @param memberName  脚本中使用的成员名称
     * @param isMethod    true=方法调用, false=字段访问
     * @return 映射后的实际名称，返回 null 表示无映射（使用原名）
     */
    String resolve(Class<?> targetClass, String memberName, boolean isMethod);
}
