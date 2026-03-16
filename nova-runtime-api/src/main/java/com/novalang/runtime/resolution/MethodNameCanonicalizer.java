package com.novalang.runtime.resolution;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Centralized method-name canonicalization.
 *
 * <p>Lookup sites should always try the exact source name first, then fall back
 * to the canonical target if the source name is a recognized alias.</p>
 */
public final class MethodNameCanonicalizer {

    private static final Map<String, String> ALIASES;
    /** 反向映射：canonical → alias（用于 lookupCandidates 双向查找） */
    private static final Map<String, String> REVERSE;

    static {
        Map<String, String> aliases = new LinkedHashMap<String, String>();
        aliases.put("eq", "equals");
        aliases.put("uppercase", "toUpperCase");
        aliases.put("lowercase", "toLowerCase");
        ALIASES = Collections.unmodifiableMap(aliases);
        Map<String, String> reverse = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> e : aliases.entrySet()) {
            reverse.put(e.getValue(), e.getKey());
        }
        REVERSE = Collections.unmodifiableMap(reverse);
    }

    private MethodNameCanonicalizer() {}

    public static String canonicalName(String methodName) {
        String canonical = ALIASES.get(methodName);
        return canonical != null ? canonical : methodName;
    }

    public static String aliasTarget(String methodName) {
        return ALIASES.get(methodName);
    }

    public static List<String> lookupCandidates(String methodName) {
        String canonical = canonicalName(methodName);
        if (!canonical.equals(methodName)) {
            return Arrays.asList(methodName, canonical);
        }
        // 反向查找：toUpperCase → [toUpperCase, uppercase]
        String alias = REVERSE.get(methodName);
        if (alias != null) {
            return Arrays.asList(methodName, alias);
        }
        return Collections.singletonList(methodName);
    }
}
