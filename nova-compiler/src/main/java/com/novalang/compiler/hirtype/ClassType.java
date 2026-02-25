package com.novalang.compiler.hirtype;

import java.util.Collections;
import java.util.List;

/**
 * 类类型（包含泛型参数）。
 */
public class ClassType extends HirType {

    private final String name;
    private final List<HirType> typeArgs;

    public ClassType(String name, List<HirType> typeArgs, boolean nullable) {
        super(nullable);
        this.name = name;
        this.typeArgs = typeArgs != null ? typeArgs : Collections.emptyList();
    }

    public ClassType(String name, boolean nullable) {
        this(name, Collections.emptyList(), nullable);
    }

    public ClassType(String name) {
        this(name, Collections.emptyList(), false);
    }

    public String getName() {
        return name;
    }

    public List<HirType> getTypeArgs() {
        return typeArgs;
    }

    @Override
    public HirType withNullable(boolean nullable) {
        return new ClassType(name, typeArgs, nullable);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(name);
        if (!typeArgs.isEmpty()) {
            sb.append('<');
            for (int i = 0; i < typeArgs.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(typeArgs.get(i));
            }
            sb.append('>');
        }
        if (nullable) sb.append('?');
        return sb.toString();
    }
}
