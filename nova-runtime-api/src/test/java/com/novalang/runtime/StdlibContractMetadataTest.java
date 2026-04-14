package com.novalang.runtime;

import com.novalang.runtime.contract.NovaContract;
import com.novalang.runtime.contract.StdlibContracts;
import com.novalang.runtime.contract.ConstraintExpr;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Stdlib contract metadata")
class StdlibContractMetadataTest {

    @NovaType(name = "DemoType", description = "demo")
    static final class DemoAnnotatedType {
        @NovaMember(description = "demo member", returnType = "Int", contract = "scope.apply")
        public static int apply(Object self, Object block) {
            return 0;
        }
    }

    @Test
    @DisplayName("core contract definitions should be registered")
    void coreContractsShouldExist() {
        assertNotNull(StdlibContracts.get("scope.let"));
        assertNotNull(StdlibContracts.get("result.flatMap"));
        assertNotNull(StdlibContracts.get("list.map"));
        assertNotNull(StdlibContracts.get("map.mapValues"));
    }

    @Test
    @DisplayName("manual stdlib metadata bridge should expose core function and member contracts")
    void metadataBridgeShouldExposeCoreContracts() {
        NovaContract ok = StdlibContractMetadata.functionContract("Ok");
        assertNotNull(ok);
        assertEquals("result.ok", ok.getId());

        NovaContract apply = StdlibContractMetadata.memberContract("Any", "apply");
        assertNotNull(apply);
        assertEquals("scope.apply", apply.getId());

        NovaContract mapValues = StdlibContractMetadata.memberContract("Map", "mapValues");
        assertNotNull(mapValues);
        assertEquals("map.mapValues", mapValues.getId());
    }

    @Test
    @DisplayName("annotated types should be able to contribute member contracts through existing annotations")
    void annotatedTypesShouldContributeMemberContracts() {
        StdlibContractMetadata.scanAnnotatedClass(DemoAnnotatedType.class.getName());
        NovaContract contract = StdlibContractMetadata.memberContract("DemoType", "apply");
        assertNotNull(contract);
        assertEquals("scope.apply", contract.getId());
    }

    @Test
    @DisplayName("core contracts should expose structured constraints instead of only named markers")
    void coreContractsShouldExposeStructuredConstraints() {
        NovaContract resultFlatMap = StdlibContracts.get("result.flatMap");
        NovaContract listFlatMap = StdlibContracts.get("list.flatMap");

        assertNotNull(resultFlatMap);
        assertNotNull(listFlatMap);
        assertTrue(resultFlatMap.getConstraints().stream().anyMatch(ConstraintExpr.ReceiverBaseTypeConstraint.class::isInstance));
        assertTrue(resultFlatMap.getConstraints().stream().anyMatch(ConstraintExpr.LambdaReturnsBaseTypeConstraint.class::isInstance));
        assertTrue(listFlatMap.getConstraints().stream().anyMatch(ConstraintExpr.LambdaReturnsCollectionConstraint.class::isInstance));
    }
}
