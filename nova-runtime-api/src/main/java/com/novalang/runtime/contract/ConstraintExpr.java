package com.novalang.runtime.contract;

/**
 * Declarative validation marker attached to a contract.
 */
public interface ConstraintExpr {

    static ConstraintExpr receiverBaseType(String baseType) {
        return new ReceiverBaseTypeConstraint(baseType);
    }

    static ConstraintExpr lambdaReturnsBaseType(String baseType) {
        return new LambdaReturnsBaseTypeConstraint(baseType);
    }

    static ConstraintExpr lambdaReturnsCollection() {
        return LambdaReturnsCollectionConstraint.INSTANCE;
    }

    static ConstraintExpr named(String name) {
        return new NamedConstraint(name);
    }

    final class ReceiverBaseTypeConstraint implements ConstraintExpr {
        private final String baseType;

        public ReceiverBaseTypeConstraint(String baseType) {
            this.baseType = baseType;
        }

        public String getBaseType() {
            return baseType;
        }
    }

    final class LambdaReturnsBaseTypeConstraint implements ConstraintExpr {
        private final String baseType;

        public LambdaReturnsBaseTypeConstraint(String baseType) {
            this.baseType = baseType;
        }

        public String getBaseType() {
            return baseType;
        }
    }

    enum LambdaReturnsCollectionConstraint implements ConstraintExpr {
        INSTANCE
    }

    final class NamedConstraint implements ConstraintExpr {
        private final String name;

        public NamedConstraint(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }
}
