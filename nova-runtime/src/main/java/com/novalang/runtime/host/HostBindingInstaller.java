package com.novalang.runtime.host;

import com.novalang.runtime.AbstractNovaValue;
import com.novalang.runtime.Nova;
import com.novalang.runtime.NovaNull;
import com.novalang.runtime.NovaValue;
import com.novalang.runtime.interpreter.NovaNativeFunction;

import java.util.ArrayList;
import java.util.List;

public final class HostBindingInstaller {
    private HostBindingInstaller() {}

    public static void install(Nova nova, HostBindingRegistry registry) {
        installNamespace(nova, registry, "default");
    }

    public static void installNamespace(Nova nova, HostBindingRegistry registry, String namespaceName) {
        if (nova == null) {
            throw new IllegalArgumentException("nova must not be null");
        }
        if (registry == null) {
            throw new IllegalArgumentException("registry must not be null");
        }

        HostNamespaceDescriptor namespace = registry.resolveNamespace(namespaceName);
        for (HostSymbolDescriptor symbol : namespace.getGlobals()) {
            installSymbol(nova, symbol);
        }
    }

    private static void installSymbol(Nova nova, HostSymbolDescriptor symbol) {
        if (symbol instanceof HostVariableDescriptor) {
            installVariable(nova, (HostVariableDescriptor) symbol);
            return;
        }
        if (symbol instanceof HostPropertyDescriptor) {
            HostPropertyDescriptor property = (HostPropertyDescriptor) symbol;
            throw new IllegalStateException("Top-level property cannot be installed without runtime value: " + property.getName());
        }
        if (symbol instanceof HostFunctionDescriptor) {
            installFunction(nova, (HostFunctionDescriptor) symbol);
            return;
        }
        if (symbol instanceof HostObjectDescriptor) {
            installObject(nova, (HostObjectDescriptor) symbol);
        }
    }

    private static void installVariable(Nova nova, HostVariableDescriptor variable) {
        Object value = resolveRuntimeValue(variable.getValue(), variable.getSupplier(), variable.getName());
        if (variable.isMutable()) {
            nova.set(variable.getName(), value);
        } else {
            nova.defineVal(variable.getName(), value);
        }
    }

    private static void installObject(Nova nova, HostObjectDescriptor objectDescriptor) {
        Object value = resolveRuntimeValue(objectDescriptor.getValue(), objectDescriptor.getSupplier(), objectDescriptor.getName());
        nova.defineVal(objectDescriptor.getName(), value);
    }

    private static void installFunction(Nova nova, HostFunctionDescriptor function) {
        if (function.getInvoker() == null) {
            throw new IllegalStateException("Host function has no invoker: " + function.getName());
        }

        int arity = function.isVararg() ? -1 : function.getParameters().size();
        NovaNativeFunction nativeFunction = new NovaNativeFunction(function.getName(), arity, (ctx, args) -> {
            try {
                Object[] javaArgs = new Object[args.size()];
                for (int i = 0; i < args.size(); i++) {
                    NovaValue arg = args.get(i);
                    javaArgs[i] = arg != null ? arg.toJavaValue() : null;
                }

                Object result = function.getInvoker().invoke(javaArgs);
                return result == null ? NovaNull.UNIT : AbstractNovaValue.fromJava(result);
            } catch (Exception e) {
                throw new RuntimeException("Failed to invoke host function '" + function.getName() + "'", e);
            }
        });

        nova.defineVal(function.getName(), nativeFunction);
    }

    private static Object resolveRuntimeValue(Object value, java.util.function.Supplier<?> supplier, String name) {
        if (supplier != null) {
            return supplier.get();
        }
        if (value != null) {
            return value;
        }
        throw new IllegalStateException("Host binding has no runtime value: " + name);
    }
}
