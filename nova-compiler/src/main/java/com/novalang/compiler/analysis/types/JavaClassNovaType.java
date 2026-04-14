package com.novalang.compiler.analysis.types;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Structured NovaType for Java reference types resolved during semantic analysis.
 */
public final class JavaClassNovaType extends ClassNovaType {

    private final String qualifiedName;
    private final JavaTypeDescriptor descriptor;

    public JavaClassNovaType(JavaTypeDescriptor descriptor, boolean nullable) {
        this(descriptor, Collections.<NovaTypeArgument>emptyList(), nullable);
    }

    public JavaClassNovaType(JavaTypeDescriptor descriptor, List<NovaTypeArgument> typeArgs, boolean nullable) {
        super(descriptor != null ? descriptor.getSimpleName() : null, typeArgs, nullable);
        this.qualifiedName = descriptor != null ? descriptor.getQualifiedName() : null;
        this.descriptor = descriptor;
    }

    public String getQualifiedName() {
        return qualifiedName;
    }

    public JavaTypeDescriptor getDescriptor() {
        return descriptor;
    }

    @Override
    public NovaType withNullable(boolean nullable) {
        if (isNullable() == nullable) return this;
        return new JavaClassNovaType(descriptor, getTypeArgs(), nullable);
    }

    @Override
    public String toDisplayString() {
        StringBuilder sb = new StringBuilder(getName());
        if (hasTypeArgs()) {
            sb.append('<');
            for (int i = 0; i < getTypeArgs().size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(getTypeArgs().get(i).toDisplayString());
            }
            sb.append('>');
        }
        if (isNullable()) sb.append('?');
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JavaClassNovaType)) return false;
        JavaClassNovaType that = (JavaClassNovaType) o;
        return isNullable() == that.isNullable()
                && Objects.equals(getName(), that.getName())
                && Objects.equals(getTypeArgs(), that.getTypeArgs())
                && Objects.equals(qualifiedName, that.qualifiedName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getName(), getTypeArgs(), isNullable(), qualifiedName);
    }
}
