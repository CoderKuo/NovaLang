package com.novalang.ir.mir;

/**
 * INVOKE_DYNAMIC 指令的元数据。
 * 存储在 MirInst.extra 中，供 MirCodeGenerator 生成 invokedynamic 字节码。
 */
public final class InvokeDynamicInfo {

    /** 方法名（作为 invokedynamic 的 name 参数） */
    public final String methodName;

    /** bootstrap 类的内部名（如 "nova/runtime/NovaBootstrap"） */
    public final String bootstrapClass;

    /** bootstrap 方法名（如 "bootstrapInvoke"） */
    public final String bootstrapMethod;

    /** 调用点描述符（如 "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"） */
    public final String descriptor;

    public InvokeDynamicInfo(String methodName, String bootstrapClass,
                             String bootstrapMethod, String descriptor) {
        this.methodName = methodName;
        this.bootstrapClass = bootstrapClass;
        this.bootstrapMethod = bootstrapMethod;
        this.descriptor = descriptor;
    }

    @Override
    public String toString() {
        return "invokedynamic " + methodName + " " + descriptor
                + " [" + bootstrapClass + "." + bootstrapMethod + "]";
    }
}
