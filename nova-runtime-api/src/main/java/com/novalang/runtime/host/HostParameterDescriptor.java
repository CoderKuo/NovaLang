package com.novalang.runtime.host;

public final class HostParameterDescriptor {
    private final String name;
    private final HostTypeRef type;
    private final boolean vararg;

    public HostParameterDescriptor(String name, HostTypeRef type, boolean vararg) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Parameter name must not be empty");
        }
        this.name = name;
        this.type = type != null ? type : HostTypes.ANY;
        this.vararg = vararg;
    }

    public String getName() {
        return name;
    }

    public HostTypeRef getType() {
        return type;
    }

    public boolean isVararg() {
        return vararg;
    }
}
