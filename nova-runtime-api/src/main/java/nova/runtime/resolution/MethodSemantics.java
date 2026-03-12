package nova.runtime.resolution;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Shared semantic facts for method lookup and lowering.
 */
public final class MethodSemantics {

    private static final Set<String> SCOPE_FUNCTIONS = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList("let", "also", "run", "apply", "takeIf", "takeUnless")));

    private static final IntrinsicVirtualMethod TO_STRING =
            new IntrinsicVirtualMethod("java/lang/Object", "()Ljava/lang/String;");
    private static final IntrinsicVirtualMethod EQUALS =
            new IntrinsicVirtualMethod("java/lang/Object", "(Ljava/lang/Object;)Z");
    private static final IntrinsicVirtualMethod HASH_CODE =
            new IntrinsicVirtualMethod("java/lang/Object", "()I");

    private MethodSemantics() {}

    public static boolean isScopeFunction(String methodName, int argCount) {
        return argCount == 1 && SCOPE_FUNCTIONS.contains(MethodNameCanonicalizer.canonicalName(methodName));
    }

    public static IntrinsicVirtualMethod resolveIntrinsicVirtual(String methodName, int argCount) {
        String canonical = MethodNameCanonicalizer.canonicalName(methodName);
        if (argCount == 0 && "toString".equals(canonical)) return TO_STRING;
        if (argCount == 1 && "equals".equals(canonical)) return EQUALS;
        if (argCount == 0 && "hashCode".equals(canonical)) return HASH_CODE;
        return null;
    }

    public static final class IntrinsicVirtualMethod {
        private final String owner;
        private final String descriptor;

        private IntrinsicVirtualMethod(String owner, String descriptor) {
            this.owner = owner;
            this.descriptor = descriptor;
        }

        public String getOwner() {
            return owner;
        }

        public String getDescriptor() {
            return descriptor;
        }
    }
}
