package com.novalang.compiler.ast.decl;

/**
 * 解构声明中的单个条目。
 * <ul>
 *   <li>位置解构: localName="a", propertyName=null → componentN()</li>
 *   <li>名称解构: localName="mail", propertyName="email" → obj.email</li>
 *   <li>跳过:     localName=null, propertyName=null → _</li>
 * </ul>
 */
public class DestructuringEntry {
    private final String localName;
    private final String propertyName;

    public DestructuringEntry(String localName, String propertyName) {
        this.localName = localName;
        this.propertyName = propertyName;
    }

    public String getLocalName() { return localName; }
    public String getPropertyName() { return propertyName; }
    public boolean isNameBased() { return propertyName != null; }
    public boolean isSkip() { return localName == null && propertyName == null; }
}
