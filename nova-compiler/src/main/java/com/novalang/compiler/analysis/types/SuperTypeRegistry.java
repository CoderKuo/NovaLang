package com.novalang.compiler.analysis.types;

import java.util.*;

/**
 * 管理类型继承关系，用于子类型判断。
 * 内置常见类型继承关系 + 运行时注册用户定义类。
 */
public final class SuperTypeRegistry {

    private final Map<String, String> superClasses = new LinkedHashMap<String, String>();
    private final Map<String, List<String>> interfaces = new LinkedHashMap<String, List<String>>();

    public SuperTypeRegistry() {
        // 内置继承关系
        superClasses.put("Int", "Number");
        superClasses.put("Long", "Number");
        superClasses.put("Float", "Number");
        superClasses.put("Double", "Number");
        superClasses.put("Char", "Any");
        superClasses.put("Boolean", "Any");
        superClasses.put("String", "Any");
        superClasses.put("Number", "Any");
        superClasses.put("List", "Any");
        superClasses.put("Set", "Any");
        superClasses.put("Map", "Any");
        superClasses.put("Range", "Any");
        superClasses.put("Pair", "Any");
        superClasses.put("Result", "Any");
        superClasses.put("Exception", "Any");

        // Comparable 接口
        interfaces.put("Int", Collections.singletonList("Comparable"));
        interfaces.put("Long", Collections.singletonList("Comparable"));
        interfaces.put("Float", Collections.singletonList("Comparable"));
        interfaces.put("Double", Collections.singletonList("Comparable"));
        interfaces.put("String", Collections.singletonList("Comparable"));
    }

    /**
     * 注册用户定义类的继承关系。
     */
    public void registerClass(String name, String superClass, List<String> ifaces) {
        if (superClass != null) {
            superClasses.put(name, superClass);
        } else if (!superClasses.containsKey(name)) {
            // 默认继承 Any
            superClasses.put(name, "Any");
        }
        if (ifaces != null && !ifaces.isEmpty()) {
            List<String> existing = interfaces.get(name);
            if (existing == null) {
                interfaces.put(name, new ArrayList<String>(ifaces));
            } else {
                existing.addAll(ifaces);
            }
        }
    }

    /**
     * 判断 sub 是否是 sup 的子类型（递归向上查找）。
     */
    public boolean isSubtype(String sub, String sup) {
        if (sub == null || sup == null) return false;
        if (sub.equals(sup)) return true;
        if ("Any".equals(sup)) return true;
        if ("Nothing".equals(sub)) return true;

        // 检查直接继承
        String parent = superClasses.get(sub);
        if (parent != null && isSubtype(parent, sup)) return true;

        // 检查接口实现
        List<String> ifaces = interfaces.get(sub);
        if (ifaces != null) {
            for (String iface : ifaces) {
                if (isSubtype(iface, sup)) return true;
            }
        }
        return false;
    }
}
