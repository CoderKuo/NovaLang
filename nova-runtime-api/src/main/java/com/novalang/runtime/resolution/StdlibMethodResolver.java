package com.novalang.runtime.resolution;

import com.novalang.runtime.stdlib.StdlibRegistry;

import java.util.List;

/**
 * Shared stdlib-extension lookup helpers used by interpreter, lowering and
 * compiled runtime.
 */
public final class StdlibMethodResolver {

    private StdlibMethodResolver() {}

    public static StdlibRegistry.ExtensionMethodInfo resolveByClass(Class<?> receiverClass,
                                                                    String methodName,
                                                                    int arity) {
        if (receiverClass == null) return null;
        List<String> candidates = MethodNameCanonicalizer.lookupCandidates(methodName);
        for (int i = 0; i < candidates.size(); i++) {
            StdlibRegistry.ExtensionMethodInfo info =
                    StdlibRegistry.findExtensionMethod(receiverClass, candidates.get(i), arity);
            if (info != null) return info;
        }
        return null;
    }

    public static StdlibRegistry.ExtensionMethodInfo resolveByOwner(String ownerInternalName,
                                                                    String methodName,
                                                                    int arity) {
        if (ownerInternalName == null) return null;
        List<String> candidates = MethodNameCanonicalizer.lookupCandidates(methodName);
        for (int i = 0; i < candidates.size(); i++) {
            StdlibRegistry.ExtensionMethodInfo info =
                    StdlibRegistry.getExtensionMethod(ownerInternalName, candidates.get(i), arity);
            if (info != null) return info;
        }
        return null;
    }
}
