package com.novalang.ir.mir;

import java.util.List;

/**
 * JVM 方法描述符构建工具类。
 * 消除手工 StringBuilder 循环拼接描述符的重复模式。
 */
public final class MethodDescriptor {

    // ── 常用描述符常量 ──
    public static final String OBJECT_DESC = "Ljava/lang/Object;";
    public static final String VOID_DESC = "V";
    public static final String NO_ARG_OBJECT = "()Ljava/lang/Object;";
    public static final String NO_ARG_VOID = "()V";

    private final List<MirType> paramTypes;
    private final MirType returnType;

    private MethodDescriptor(List<MirType> paramTypes, MirType returnType) {
        this.paramTypes = paramTypes;
        this.returnType = returnType;
    }

    // ── 工厂方法 ──

    /** 使用指定的参数类型和返回类型构建描述符。 */
    public static MethodDescriptor of(List<MirType> params, MirType ret) {
        return new MethodDescriptor(params, ret);
    }

    /** 所有参数为 Object，返回 Object 的描述符。 */
    public static MethodDescriptor allObject(int argCount) {
        return new MethodDescriptor(
                nCopies(argCount, MirType.ofObject("java/lang/Object")),
                MirType.ofObject("java/lang/Object")
        );
    }

    /** 所有参数为 Object，返回 void 的描述符（构造器等）。 */
    public static MethodDescriptor allObjectVoid(int argCount) {
        return new MethodDescriptor(
                nCopies(argCount, MirType.ofObject("java/lang/Object")),
                MirType.ofVoid()
        );
    }

    /** 构造器描述符，等价于 allObjectVoid。 */
    public static MethodDescriptor ctorDesc(int argCount) {
        return allObjectVoid(argCount);
    }

    /** 从 MirFunction 的参数和返回类型构建描述符。 */
    public static MethodDescriptor fromMirFunction(MirFunction func) {
        java.util.ArrayList<MirType> params = new java.util.ArrayList<>();
        for (MirParam p : func.getParams()) {
            params.add(p.getType());
        }
        return new MethodDescriptor(params, func.getReturnType());
    }

    // ── 快捷静态方法（直接返回 JVM 描述符字符串） ──

    /** 快速生成全 Object 参数、Object 返回的 JVM 描述符。 */
    public static String allObjectDesc(int argCount) {
        if (argCount == 0) return NO_ARG_OBJECT;
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < argCount; i++) sb.append(OBJECT_DESC);
        sb.append(')').append(OBJECT_DESC);
        return sb.toString();
    }

    /** 快速生成全 Object 参数、void 返回的 JVM 描述符（构造器）。 */
    public static String allObjectVoidDesc(int argCount) {
        if (argCount == 0) return NO_ARG_VOID;
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < argCount; i++) sb.append(OBJECT_DESC);
        sb.append(')').append(VOID_DESC);
        return sb.toString();
    }

    // ── 输出 ──

    /** 生成 JVM 方法描述符字符串，例如 "(Ljava/lang/Object;I)Ljava/lang/Object;" */
    public String toJvmDescriptor() {
        StringBuilder sb = new StringBuilder("(");
        for (MirType pt : paramTypes) {
            sb.append(pt.getDescriptor());
        }
        sb.append(')');
        sb.append(returnType.getDescriptor());
        return sb.toString();
    }

    /**
     * 生成 JVM 方法描述符，但仅 INT 使用原始描述符，其余使用 Object。
     * 对应 buildHirMethodDescriptor / buildNativeStaticDescriptor 的逻辑。
     */
    public String toJvmDescriptorIntOnly() {
        StringBuilder sb = new StringBuilder("(");
        for (MirType pt : paramTypes) {
            if (pt.getKind() == MirType.Kind.INT) {
                sb.append(pt.getDescriptor());
            } else {
                sb.append(OBJECT_DESC);
            }
        }
        sb.append(')');
        MirType.Kind retKind = returnType.getKind();
        if (retKind == MirType.Kind.VOID) {
            sb.append(VOID_DESC);
        } else if (returnType.isPrimitive()) {
            sb.append(returnType.getDescriptor());
        } else {
            sb.append(OBJECT_DESC);
        }
        return sb.toString();
    }

    /**
     * 生成全 Object 参数的描述符，返回类型根据 MirType 决定（VOID→V，否则→Object）。
     * 对应 MirCodeGenerator.buildMethodDescriptor() 的逻辑。
     */
    public String toAllObjectDescriptor() {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < paramTypes.size(); i++) {
            sb.append(OBJECT_DESC);
        }
        sb.append(')');
        if (returnType.getKind() == MirType.Kind.VOID) {
            sb.append(VOID_DESC);
        } else {
            sb.append(OBJECT_DESC);
        }
        return sb.toString();
    }

    public List<MirType> getParamTypes() { return paramTypes; }
    public MirType getReturnType() { return returnType; }

    @Override
    public String toString() {
        return toJvmDescriptor();
    }

    // ── 内部工具 ──

    private static List<MirType> nCopies(int n, MirType type) {
        java.util.ArrayList<MirType> list = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) list.add(type);
        return list;
    }
}
