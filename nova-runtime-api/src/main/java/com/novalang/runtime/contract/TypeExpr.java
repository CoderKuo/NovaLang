package com.novalang.runtime.contract;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Declarative type expression used by stdlib contracts.
 */
public interface TypeExpr {

    static TypeExpr concrete(String name, TypeExpr... typeArguments) {
        return new ConcreteTypeExpr(name, Arrays.asList(typeArguments));
    }

    static TypeExpr typeVar(String name) {
        return new TypeVarExpr(name);
    }

    static TypeExpr receiver() {
        return ReceiverTypeExpr.INSTANCE;
    }

    static TypeExpr lambdaResult(int argumentIndex) {
        return new LambdaResultExpr(argumentIndex);
    }

    static TypeExpr nullable(TypeExpr target) {
        return new NullableExpr(target);
    }

    static TypeExpr nonNull(TypeExpr target) {
        return new NonNullExpr(target);
    }

    static TypeExpr commonSuper(TypeExpr left, TypeExpr right) {
        return new CommonSuperExpr(left, right);
    }

    static TypeExpr payloadType(TypeExpr target) {
        return new PayloadTypeExpr(target);
    }

    final class ConcreteTypeExpr implements TypeExpr {
        private final String name;
        private final List<TypeExpr> typeArguments;

        public ConcreteTypeExpr(String name, List<TypeExpr> typeArguments) {
            this.name = name;
            this.typeArguments = Collections.unmodifiableList(new ArrayList<TypeExpr>(typeArguments));
        }

        public String getName() {
            return name;
        }

        public List<TypeExpr> getTypeArguments() {
            return typeArguments;
        }
    }

    final class TypeVarExpr implements TypeExpr {
        private final String name;

        public TypeVarExpr(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    enum ReceiverTypeExpr implements TypeExpr {
        INSTANCE
    }

    final class LambdaResultExpr implements TypeExpr {
        private final int argumentIndex;

        public LambdaResultExpr(int argumentIndex) {
            this.argumentIndex = argumentIndex;
        }

        public int getArgumentIndex() {
            return argumentIndex;
        }
    }

    abstract class UnaryTypeExpr implements TypeExpr {
        private final TypeExpr target;

        protected UnaryTypeExpr(TypeExpr target) {
            this.target = target;
        }

        public TypeExpr getTarget() {
            return target;
        }
    }

    final class NullableExpr extends UnaryTypeExpr {
        public NullableExpr(TypeExpr target) { super(target); }
    }

    final class NonNullExpr extends UnaryTypeExpr {
        public NonNullExpr(TypeExpr target) { super(target); }
    }

    final class PayloadTypeExpr extends UnaryTypeExpr {
        public PayloadTypeExpr(TypeExpr target) { super(target); }
    }

    final class CommonSuperExpr implements TypeExpr {
        private final TypeExpr left;
        private final TypeExpr right;

        public CommonSuperExpr(TypeExpr left, TypeExpr right) {
            this.left = left;
            this.right = right;
        }

        public TypeExpr getLeft() {
            return left;
        }

        public TypeExpr getRight() {
            return right;
        }
    }
}
