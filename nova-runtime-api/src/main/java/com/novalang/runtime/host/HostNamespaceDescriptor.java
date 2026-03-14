package com.novalang.runtime.host;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class HostNamespaceDescriptor {
    private final String name;
    private final List<String> extendsNamespaces;
    private final List<HostSymbolDescriptor> globals;

    public HostNamespaceDescriptor(String name, List<String> extendsNamespaces, List<HostSymbolDescriptor> globals) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Namespace name must not be empty");
        }
        this.name = name;
        this.extendsNamespaces = Collections.unmodifiableList(new ArrayList<String>(extendsNamespaces != null ? extendsNamespaces : Collections.<String>emptyList()));
        this.globals = Collections.unmodifiableList(new ArrayList<HostSymbolDescriptor>(globals != null ? globals : Collections.<HostSymbolDescriptor>emptyList()));
    }

    public String getName() {
        return name;
    }

    public List<String> getExtendsNamespaces() {
        return extendsNamespaces;
    }

    public List<HostSymbolDescriptor> getGlobals() {
        return globals;
    }
}
