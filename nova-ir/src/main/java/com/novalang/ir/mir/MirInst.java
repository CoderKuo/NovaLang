package com.novalang.ir.mir;

import com.novalang.compiler.ast.SourceLocation;

import java.util.List;

/**
 * MIR 指令。
 */
public class MirInst {

    private final MirOp op;
    private final int dest;            // 目标局部变量索引（-1 = 无返回值）
    private final int[] operands;      // 操作数（局部变量索引）
    private final Object extra;        // 额外数据（常量值、字段名、方法名等）
    private final SourceLocation location;
    /** 运行时缓存槽（解释器使用，惰性初始化，如预解析的调用站点） */
    public Object cache;

    // INVOKE_STATIC 特殊标记分类（编译期设置，避免解释器运行时字符串匹配）
    public static final byte SK_NORMAL = 0;
    public static final byte SK_SCOPE_CALL = 1;    // $ScopeCall
    public static final byte SK_PARTIAL_APP = 2;   // $PartialApplication|mask
    public static final byte SK_ENV_ACCESS = 3;    // $ENV| 或 nova/runtime/NovaScriptContext|

    /** 特殊标记类型（仅 INVOKE_STATIC 使用，0 = 普通调用） */
    public byte specialKind;

    /** CONST_INT 预拆箱值，避免运行时 Integer → int 拆箱 */
    public int extraInt;

    public MirInst(MirOp op, int dest, int[] operands, Object extra, SourceLocation location) {
        this.op = op;
        this.dest = dest;
        this.operands = operands;
        this.extra = extra;
        this.location = location;
        if (extra instanceof Integer) this.extraInt = (int) extra;
    }

    public MirOp getOp() { return op; }
    public int getDest() { return dest; }
    public int[] getOperands() { return operands; }
    public Object getExtra() { return extra; }
    public SourceLocation getLocation() { return location; }

    /**
     * 获取第 n 个操作数。
     */
    public int operand(int n) {
        return operands[n];
    }

    /**
     * extra 作为指定类型。
     */
    @SuppressWarnings("unchecked")
    public <T> T extraAs() {
        return (T) extra;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (dest >= 0) sb.append('%').append(dest).append(" = ");
        sb.append(op.name());
        if (operands != null) {
            for (int i = 0; i < operands.length; i++) {
                sb.append(i == 0 ? " %" : ", %").append(operands[i]);
            }
        }
        if (extra != null) sb.append(" [").append(extra).append(']');
        return sb.toString();
    }
}
