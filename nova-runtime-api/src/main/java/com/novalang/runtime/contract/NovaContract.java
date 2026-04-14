package com.novalang.runtime.contract;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

/**
 * Declarative contract definition for stdlib functions and members.
 */
public final class NovaContract {
    public enum Kind {
        FUNCTION,
        MEMBER,
        INTRINSIC
    }

    public enum Tag {
        SCOPE_FUNCTION,
        RETURNS_RECEIVER,
        RETURNS_NULLABLE_RECEIVER,
        RETURNS_LAMBDA_RESULT,
        RESULT_TRANSFORM,
        RESULT_FLATMAP,
        COLLECTION_MAP,
        COLLECTION_MAP_NOT_NULL,
        COLLECTION_FILTER,
        COLLECTION_FLATMAP,
        MAP_KEY_TRANSFORM,
        MAP_VALUE_TRANSFORM,
        MAP_FILTER,
        SUPPORTS_ENTRY_LAMBDA,
        SUPPORTS_BI_LAMBDA,
        SUPPORTS_RECEIVER_LAMBDA,
        PURE_TYPE_RULE,
        LOWERING_INTRINSIC
    }

    public enum LambdaShape {
        IMPLICIT_IT,
        EXPLICIT_PARAMS,
        RECEIVER_BLOCK,
        ZERO_ARG_BLOCK
    }

    public static final class TypeParamDef {
        private final String name;
        private final TypeExpr upperBound;

        public TypeParamDef(String name, TypeExpr upperBound) {
            this.name = name;
            this.upperBound = upperBound;
        }

        public String getName() {
            return name;
        }

        public TypeExpr getUpperBound() {
            return upperBound;
        }
    }

    public static final class ReceiverSpec {
        private final TypeExpr type;

        public ReceiverSpec(TypeExpr type) {
            this.type = type;
        }

        public TypeExpr getType() {
            return type;
        }
    }

    public abstract static class ArgumentSpec {
        private final String name;

        protected ArgumentSpec(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    public static final class ValueArgSpec extends ArgumentSpec {
        private final TypeExpr type;

        public ValueArgSpec(String name, TypeExpr type) {
            super(name);
            this.type = type;
        }

        public TypeExpr getType() {
            return type;
        }
    }

    public static final class LambdaVariantSpec {
        private final LambdaShape shape;
        private final TypeExpr receiverType;
        private final List<TypeExpr> paramTypes;
        private final TypeExpr returnType;

        public LambdaVariantSpec(LambdaShape shape, TypeExpr receiverType,
                                 List<TypeExpr> paramTypes, TypeExpr returnType) {
            this.shape = shape;
            this.receiverType = receiverType;
            this.paramTypes = Collections.unmodifiableList(new ArrayList<TypeExpr>(paramTypes));
            this.returnType = returnType;
        }

        public LambdaShape getShape() {
            return shape;
        }

        public TypeExpr getReceiverType() {
            return receiverType;
        }

        public List<TypeExpr> getParamTypes() {
            return paramTypes;
        }

        public TypeExpr getReturnType() {
            return returnType;
        }
    }

    public static final class LambdaArgSpec extends ArgumentSpec {
        private final List<LambdaVariantSpec> variants;

        public LambdaArgSpec(String name, List<LambdaVariantSpec> variants) {
            super(name);
            this.variants = Collections.unmodifiableList(new ArrayList<LambdaVariantSpec>(variants));
        }

        public List<LambdaVariantSpec> getVariants() {
            return variants;
        }
    }

    private final String id;
    private final Kind kind;
    private final List<TypeParamDef> typeParams;
    private final ReceiverSpec receiver;
    private final List<ArgumentSpec> arguments;
    private final TypeExpr returnType;
    private final List<ConstraintExpr> constraints;
    private final EnumSet<Tag> tags;

    private NovaContract(Builder builder) {
        this.id = builder.id;
        this.kind = builder.kind;
        this.typeParams = Collections.unmodifiableList(new ArrayList<TypeParamDef>(builder.typeParams));
        this.receiver = builder.receiver;
        this.arguments = Collections.unmodifiableList(new ArrayList<ArgumentSpec>(builder.arguments));
        this.returnType = builder.returnType;
        this.constraints = Collections.unmodifiableList(new ArrayList<ConstraintExpr>(builder.constraints));
        this.tags = builder.tags.isEmpty() ? EnumSet.noneOf(Tag.class) : EnumSet.copyOf(builder.tags);
    }

    public String getId() {
        return id;
    }

    public Kind getKind() {
        return kind;
    }

    public List<TypeParamDef> getTypeParams() {
        return typeParams;
    }

    public ReceiverSpec getReceiver() {
        return receiver;
    }

    public List<ArgumentSpec> getArguments() {
        return arguments;
    }

    public TypeExpr getReturnType() {
        return returnType;
    }

    public List<ConstraintExpr> getConstraints() {
        return constraints;
    }

    public EnumSet<Tag> getTags() {
        return tags.clone();
    }

    public boolean hasTag(Tag tag) {
        return tags.contains(tag);
    }

    public static Builder member(String id) {
        return new Builder(id, Kind.MEMBER);
    }

    public static Builder function(String id) {
        return new Builder(id, Kind.FUNCTION);
    }

    public static LambdaVariantSpec variant(LambdaShape shape, TypeExpr returnType, TypeExpr... paramTypes) {
        return new LambdaVariantSpec(shape, null, Arrays.asList(paramTypes), returnType);
    }

    public static LambdaVariantSpec receiverVariant(LambdaShape shape, TypeExpr receiverType,
                                                    TypeExpr returnType, TypeExpr... paramTypes) {
        return new LambdaVariantSpec(shape, receiverType, Arrays.asList(paramTypes), returnType);
    }

    public static final class Builder {
        private final String id;
        private final Kind kind;
        private final List<TypeParamDef> typeParams = new ArrayList<TypeParamDef>();
        private ReceiverSpec receiver;
        private final List<ArgumentSpec> arguments = new ArrayList<ArgumentSpec>();
        private TypeExpr returnType = TypeExpr.concrete("Any");
        private final List<ConstraintExpr> constraints = new ArrayList<ConstraintExpr>();
        private final EnumSet<Tag> tags = EnumSet.noneOf(Tag.class);

        private Builder(String id, Kind kind) {
            this.id = id;
            this.kind = kind;
        }

        public Builder typeParam(String name) {
            typeParams.add(new TypeParamDef(name, TypeExpr.concrete("Any")));
            return this;
        }

        public Builder typeParams(String... names) {
            for (String name : names) typeParam(name);
            return this;
        }

        public Builder receiver(TypeExpr type) {
            this.receiver = new ReceiverSpec(type);
            return this;
        }

        public Builder valueArg(String name, TypeExpr type) {
            arguments.add(new ValueArgSpec(name, type));
            return this;
        }

        public Builder lambdaArg(String name, LambdaVariantSpec... variants) {
            arguments.add(new LambdaArgSpec(name, Arrays.asList(variants)));
            return this;
        }

        public Builder returns(TypeExpr returnType) {
            this.returnType = returnType;
            return this;
        }

        public Builder constraint(ConstraintExpr constraint) {
            constraints.add(constraint);
            return this;
        }

        public Builder tag(Tag... newTags) {
            tags.addAll(Arrays.asList(newTags));
            return this;
        }

        public NovaContract build() {
            return new NovaContract(this);
        }
    }
}
