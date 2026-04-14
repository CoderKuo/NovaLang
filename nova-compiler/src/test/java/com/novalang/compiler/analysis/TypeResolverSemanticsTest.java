package com.novalang.compiler.analysis;

import com.novalang.compiler.analysis.types.NovaType;
import com.novalang.compiler.analysis.types.TypeResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayName("TypeResolver semantics")
class TypeResolverSemanticsTest {

    @Test
    @DisplayName("fully-qualified type names should not collapse to a visible simple name")
    void fullyQualifiedTypeNamesShouldNotCollapseToSimpleNames() {
        TypeResolver resolver = new TypeResolver();
        resolver.registerKnownType("User");
        resolver.registerKnownType("remote.User");

        NovaType resolved = resolver.resolveTypeNameReference("remote.User");

        assertNotNull(resolved, "Expected the fully-qualified type name to resolve");
        assertEquals("remote.User", resolved.toDisplayString(),
                "A fully-qualified type reference should stay absolute even if a simple name is visible");
    }
}
