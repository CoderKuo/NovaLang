package com.novalang.runtime.interpreter;

final class LookupMissDetector {

    private LookupMissDetector() {}

    static boolean isLookupMiss(NovaRuntimeException e) {
        String message = e.getMessage();
        if (message == null) return false;
        return message.startsWith("Unknown member ")
                || message.startsWith("Unknown super member:")
                || message.startsWith("Unknown builder member:")
                || message.startsWith("Unknown ClassInfo member:")
                || message.startsWith("Unknown FieldInfo member:")
                || message.startsWith("Unknown MethodInfo member:")
                || message.startsWith("Undefined property:")
                || message.startsWith("Field not found:")
                || message.startsWith("Method not found:");
    }
}
