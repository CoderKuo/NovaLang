package nova.runtime.resolution;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MethodResolutionSupportTest {

    @Test
    void aliasCandidatesPreserveExactFirst() {
        assertEquals("equals", MethodNameCanonicalizer.canonicalName("eq"));
        assertEquals("eq", MethodNameCanonicalizer.lookupCandidates("eq").get(0));
        assertEquals("equals", MethodNameCanonicalizer.lookupCandidates("eq").get(1));
    }

    @Test
    void scopeFunctionsUseCanonicalName() {
        assertTrue(MethodSemantics.isScopeFunction("run", 1));
        assertTrue(MethodSemantics.isScopeFunction("apply", 1));
        assertFalse(MethodSemantics.isScopeFunction("run", 0));
        assertFalse(MethodSemantics.isScopeFunction("foo", 1));
    }

    @Test
    void intrinsicVirtualMethodsAreCanonicalized() {
        MethodSemantics.IntrinsicVirtualMethod eq = MethodSemantics.resolveIntrinsicVirtual("eq", 1);
        assertNotNull(eq);
        assertEquals("(Ljava/lang/Object;)Z", eq.getDescriptor());

        MethodSemantics.IntrinsicVirtualMethod hashCode = MethodSemantics.resolveIntrinsicVirtual("hashCode", 0);
        assertNotNull(hashCode);
        assertEquals("()I", hashCode.getDescriptor());
    }
}
