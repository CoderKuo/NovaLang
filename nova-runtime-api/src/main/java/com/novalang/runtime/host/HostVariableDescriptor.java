package com.novalang.runtime.host;

import java.util.List;
import java.util.function.Supplier;

public final class HostVariableDescriptor extends HostSymbolDescriptor {
    private final HostTypeRef type;
    private final boolean mutable;
    private final Object value;
    private final Supplier<?> supplier;

    public HostVariableDescriptor(String name,
                                  HostTypeRef type,
                                  boolean mutable,
                                  String documentation,
                                  String deprecatedMessage,
                                  List<String> examples,
                                  Object value,
                                  Supplier<?> supplier) {
        super(name, HostSymbolKind.VARIABLE, documentation, deprecatedMessage, examples);
        this.type = type != null ? type : HostTypes.ANY;
        this.mutable = mutable;
        this.value = value;
        this.supplier = supplier;
    }

    public HostTypeRef getType() {
        return type;
    }

    public boolean isMutable() {
        return mutable;
    }

    public Object getValue() {
        return value;
    }

    public Supplier<?> getSupplier() {
        return supplier;
    }
}
