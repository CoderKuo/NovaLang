package com.novalang.compiler.analysis.types;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 类/接口类型，可含泛型参数: String, List&lt;Int&gt;, Map&lt;K, V&gt;
 */
public final class ClassNovaType extends NovaType {

    private final String name;
    private final List<NovaTypeArgument> typeArgs;

    public ClassNovaType(String name, boolean nullable) {
        this(name, Collections.<NovaTypeArgument>emptyList(), nullable);
    }

    public ClassNovaType(String name, List<NovaTypeArgument> typeArgs, boolean nullable) {
        super(nullable);
        this.name = name;
        this.typeArgs = typeArgs;
    }

    public String getName() {
        return name;
    }

    public List<NovaTypeArgument> getTypeArgs() {
        return typeArgs;
    }

    public boolean hasTypeArgs() {
        return typeArgs != null && !typeArgs.isEmpty();
    }

    @Override
    public String getTypeName() {
        return name;
    }

    @Override
    public NovaType withNullable(boolean nullable) {
        if (this.nullable == nullable) return this;
        return new ClassNovaType(name, typeArgs, nullable);
    }

    @Override
    public String toDisplayString() {
        StringBuilder sb = new StringBuilder(name);
        if (hasTypeArgs()) {
            sb.append('<');
            for (int i = 0; i < typeArgs.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(typeArgs.get(i).toDisplayString());
            }
            sb.append('>');
        }
        if (nullable) sb.append('?');
        return sb.toString();
    }

    @Override
    public <R> R accept(NovaTypeVisitor<R> visitor) {
        return visitor.visitClass(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ClassNovaType)) return false;
        ClassNovaType that = (ClassNovaType) o;
        return nullable == that.nullable && name.equals(that.name) && typeArgs.equals(that.typeArgs);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, typeArgs, nullable);
    }
}
