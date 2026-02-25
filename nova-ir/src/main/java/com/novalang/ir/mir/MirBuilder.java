package com.novalang.ir.mir;

import com.novalang.compiler.ast.SourceLocation;

import java.util.List;
import java.util.Map;

/**
 * MIR 构建辅助类。
 * 封装创建指令、基本块、局部变量的便捷方法。
 */
public class MirBuilder {

    private final MirFunction function;
    private BasicBlock currentBlock;

    public MirBuilder(MirFunction function) {
        this.function = function;
        this.currentBlock = function.newBlock(); // entry block
    }

    /** 绑定到已有函数和基本块（不创建新 block） */
    public MirBuilder(MirFunction function, BasicBlock existingBlock) {
        this.function = function;
        this.currentBlock = existingBlock;
    }

    public MirFunction getFunction() { return function; }
    public BasicBlock getCurrentBlock() { return currentBlock; }

    // ========== 基本块操作 ==========

    public BasicBlock newBlock() {
        return function.newBlock();
    }

    public void switchToBlock(BasicBlock block) {
        this.currentBlock = block;
    }

    // ========== 局部变量 ==========

    public int newLocal(String name, MirType type) {
        return function.newLocal(name, type);
    }

    public int newTemp(MirType type) {
        return function.newLocal("$t" + function.getLocals().size(), type);
    }

    // ========== 指令发射 ==========

    private void emit(MirInst inst) {
        currentBlock.addInstruction(inst);
    }

    public int emitConstInt(int value, SourceLocation loc) {
        int dest = newTemp(MirType.ofInt());
        emit(new MirInst(MirOp.CONST_INT, dest, null, value, loc));
        return dest;
    }

    public int emitConstLong(long value, SourceLocation loc) {
        int dest = newTemp(MirType.ofLong());
        emit(new MirInst(MirOp.CONST_LONG, dest, null, value, loc));
        return dest;
    }

    public int emitConstFloat(float value, SourceLocation loc) {
        int dest = newTemp(MirType.ofFloat());
        emit(new MirInst(MirOp.CONST_FLOAT, dest, null, value, loc));
        return dest;
    }

    public int emitConstDouble(double value, SourceLocation loc) {
        int dest = newTemp(MirType.ofDouble());
        emit(new MirInst(MirOp.CONST_DOUBLE, dest, null, value, loc));
        return dest;
    }

    public int emitConstString(String value, SourceLocation loc) {
        int dest = newTemp(MirType.ofObject("java/lang/String"));
        emit(new MirInst(MirOp.CONST_STRING, dest, null, value, loc));
        return dest;
    }

    public int emitConstBool(boolean value, SourceLocation loc) {
        int dest = newTemp(MirType.ofBoolean());
        emit(new MirInst(MirOp.CONST_BOOL, dest, null, value, loc));
        return dest;
    }

    public int emitConstChar(char value, SourceLocation loc) {
        int dest = newTemp(MirType.ofChar());
        emit(new MirInst(MirOp.CONST_CHAR, dest, null, (int) value, loc));
        return dest;
    }

    public int emitConstNull(SourceLocation loc) {
        int dest = newTemp(MirType.ofObject("java/lang/Object"));
        emit(new MirInst(MirOp.CONST_NULL, dest, null, null, loc));
        return dest;
    }

    public int emitConstClass(String internalName, SourceLocation loc) {
        int dest = newTemp(MirType.ofObject("java/lang/Class"));
        emit(new MirInst(MirOp.CONST_CLASS, dest, null, internalName, loc));
        return dest;
    }

    public int emitMove(int src, MirType type, SourceLocation loc) {
        int dest = newTemp(type);
        emit(new MirInst(MirOp.MOVE, dest, new int[]{src}, null, loc));
        return dest;
    }

    /**
     * 将值从 src 移动到指定的 dest 局部变量。
     */
    public void emitMoveTo(int src, int dest, SourceLocation loc) {
        emit(new MirInst(MirOp.MOVE, dest, new int[]{src}, null, loc));
    }

    public int emitBinary(BinaryOp op, int left, int right, MirType resultType, SourceLocation loc) {
        int dest = newTemp(resultType);
        emit(new MirInst(MirOp.BINARY, dest, new int[]{left, right}, op, loc));
        return dest;
    }

    public int emitUnary(UnaryOp op, int operand, MirType resultType, SourceLocation loc) {
        int dest = newTemp(resultType);
        emit(new MirInst(MirOp.UNARY, dest, new int[]{operand}, op, loc));
        return dest;
    }

    public int emitNewObject(String className, int[] args, SourceLocation loc) {
        int dest = newTemp(MirType.ofObject(className));
        emit(new MirInst(MirOp.NEW_OBJECT, dest, args, className, loc));
        return dest;
    }

    public int emitGetField(int obj, String fieldName, MirType fieldType, SourceLocation loc) {
        int dest = newTemp(fieldType);
        emit(new MirInst(MirOp.GET_FIELD, dest, new int[]{obj}, fieldName, loc));
        return dest;
    }

    public void emitSetField(int obj, String fieldName, int value, SourceLocation loc) {
        emit(new MirInst(MirOp.SET_FIELD, -1, new int[]{obj, value}, fieldName, loc));
    }

    public int emitInvokeVirtual(int receiver, String methodName, int[] args,
                                  MirType returnType, SourceLocation loc) {
        int dest = newTemp(returnType);
        int[] allArgs = prependArg(receiver, args);
        emit(new MirInst(MirOp.INVOKE_VIRTUAL, dest, allArgs, methodName, loc));
        return dest;
    }

    /**
     * 发射带完整描述符的虚方法调用。
     * extra 格式: "owner|methodName|descriptor"
     */
    public int emitInvokeVirtualDesc(int receiver, String methodName, int[] args,
                                      String owner, String descriptor,
                                      MirType returnType, SourceLocation loc) {
        int dest = returnType.getKind() == MirType.Kind.VOID ? -1 : newTemp(returnType);
        int[] allArgs = prependArg(receiver, args);
        String extra = owner + "|" + methodName + "|" + descriptor;
        emit(new MirInst(MirOp.INVOKE_VIRTUAL, dest, allArgs, extra, loc));
        return dest;
    }

    public int emitInvokeStatic(String methodRef, int[] args,
                                 MirType returnType, SourceLocation loc) {
        int dest = newTemp(returnType);
        MirInst inst = new MirInst(MirOp.INVOKE_STATIC, dest, args, methodRef, loc);
        // 分类特殊标记，避免解释器运行时字符串匹配
        if (methodRef.length() > 0 && methodRef.charAt(0) == '$') {
            if ("$ScopeCall".equals(methodRef)) {
                inst.specialKind = MirInst.SK_SCOPE_CALL;
            } else if (methodRef.startsWith("$PartialApplication|")) {
                inst.specialKind = MirInst.SK_PARTIAL_APP;
            } else if (methodRef.startsWith("$ENV|")) {
                inst.specialKind = MirInst.SK_ENV_ACCESS;
            }
        } else if (methodRef.startsWith("nova/runtime/NovaScriptContext|")) {
            inst.specialKind = MirInst.SK_ENV_ACCESS;
        }
        emit(inst);
        return dest;
    }

    /**
     * GETSTATIC className.fieldName : fieldDesc
     * extra 格式: "className|fieldName|fieldDesc"
     */
    public int emitGetStatic(String className, String fieldName, String fieldDesc,
                              MirType fieldType, SourceLocation loc) {
        int dest = newTemp(fieldType);
        String extra = className + "|" + fieldName + "|" + fieldDesc;
        emit(new MirInst(MirOp.GET_STATIC, dest, null, extra, loc));
        return dest;
    }

    /**
     * 发射接口方法调用。
     * extra 格式: "owner|methodName|descriptor"
     */
    public int emitInvokeInterfaceDesc(int receiver, String methodName, int[] args,
                                        String owner, String descriptor,
                                        MirType returnType, SourceLocation loc) {
        int dest = returnType.getKind() == MirType.Kind.VOID ? -1 : newTemp(returnType);
        int[] allArgs = prependArg(receiver, args);
        String extra = owner + "|" + methodName + "|" + descriptor;
        emit(new MirInst(MirOp.INVOKE_INTERFACE, dest, allArgs, extra, loc));
        return dest;
    }

    /**
     * PUTSTATIC className.fieldName = value
     * extra 格式: "className|fieldName|fieldDesc"
     */
    public void emitSetStatic(String className, String fieldName, String fieldDesc,
                               int value, SourceLocation loc) {
        String extra = className + "|" + fieldName + "|" + fieldDesc;
        emit(new MirInst(MirOp.SET_STATIC, -1, new int[]{value}, extra, loc));
    }

    public int emitTypeCheck(int src, String typeName, SourceLocation loc) {
        int dest = newTemp(MirType.ofBoolean());
        emit(new MirInst(MirOp.TYPE_CHECK, dest, new int[]{src}, typeName, loc));
        return dest;
    }

    public int emitTypeCast(int src, String typeName, MirType resultType, SourceLocation loc) {
        int dest = newTemp(resultType);
        emit(new MirInst(MirOp.TYPE_CAST, dest, new int[]{src}, typeName, loc));
        return dest;
    }

    public int emitIndexGet(int target, int index, MirType resultType, SourceLocation loc) {
        int dest = newTemp(resultType);
        emit(new MirInst(MirOp.INDEX_GET, dest, new int[]{target, index}, null, loc));
        return dest;
    }

    public void emitIndexSet(int target, int index, int value, SourceLocation loc) {
        emit(new MirInst(MirOp.INDEX_SET, -1, new int[]{target, index, value}, null, loc));
    }

    public int emitNewArray(int sizeLocal, SourceLocation loc) {
        int dest = newTemp(MirType.ofObject("[Ljava/lang/Object;"));
        emit(new MirInst(MirOp.NEW_ARRAY, dest, new int[]{sizeLocal}, null, loc));
        return dest;
    }

    /** 创建 int[] 数组（元素类型为 int，无装箱） */
    public int emitNewIntArray(int sizeLocal, SourceLocation loc) {
        int dest = newTemp(MirType.ofObject("[I"));
        emit(new MirInst(MirOp.NEW_ARRAY, dest, new int[]{sizeLocal}, null, loc));
        return dest;
    }

    /** 创建指定元素类型的数组（arrayClassName 如 "[D", "[J", "[I" 等） */
    public int emitNewTypedArray(int sizeLocal, String arrayClassName, SourceLocation loc) {
        int dest = newTemp(MirType.ofObject(arrayClassName));
        emit(new MirInst(MirOp.NEW_ARRAY, dest, new int[]{sizeLocal}, null, loc));
        return dest;
    }

    // ========== 终止指令 ==========

    public void emitGoto(int targetBlockId, SourceLocation loc) {
        currentBlock.setTerminator(new MirTerminator.Goto(loc, targetBlockId));
    }

    public void emitBranch(int condition, int thenBlock, int elseBlock, SourceLocation loc) {
        currentBlock.setTerminator(new MirTerminator.Branch(loc, condition, thenBlock, elseBlock));
    }

    public void emitReturn(int valueLocal, SourceLocation loc) {
        currentBlock.setTerminator(new MirTerminator.Return(loc, valueLocal));
    }

    public void emitReturnVoid(SourceLocation loc) {
        currentBlock.setTerminator(new MirTerminator.Return(loc, -1));
    }

    public void emitThrow(int exceptionLocal, SourceLocation loc) {
        currentBlock.setTerminator(new MirTerminator.Throw(loc, exceptionLocal));
    }

    public void emitSwitch(int key, Map<Object, Integer> cases, int defaultBlock, SourceLocation loc) {
        currentBlock.setTerminator(new MirTerminator.Switch(loc, key, cases, defaultBlock));
    }

    public void emitUnreachable(SourceLocation loc) {
        currentBlock.setTerminator(new MirTerminator.Unreachable(loc));
    }

    // ========== 辅助 ==========

    private int[] prependArg(int first, int[] rest) {
        int[] result = new int[1 + (rest != null ? rest.length : 0)];
        result[0] = first;
        if (rest != null) System.arraycopy(rest, 0, result, 1, rest.length);
        return result;
    }
}
