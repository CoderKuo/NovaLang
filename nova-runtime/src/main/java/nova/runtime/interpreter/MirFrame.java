package nova.runtime.interpreter;

import com.novalang.ir.mir.MirFunction;
import nova.runtime.NovaInt;
import nova.runtime.NovaLong;
import nova.runtime.NovaNull;
import nova.runtime.NovaValue;

import java.util.Arrays;
import java.util.Map;

/**
 * MIR 解释器栈帧。
 *
 * <p>每次 MIR 函数调用创建一个 MirFrame，持有寄存器数组（locals）、
 * 当前基本块 ID 和块内指令指针。</p>
 *
 * <h3>双槽存储（Dual-slot Storage）</h3>
 * <p>{@code locals[]} 存储 NovaValue 引用，{@code rawLocals[]} 存储原始 int/long 值。
 * 当 {@code locals[i] == RAW_INT_MARKER} 时，表示该寄存器的值是一个未装箱的 int，
 * 实际值在 {@code rawLocals[i]} 中。热循环中的 CONST_INT/BINARY/MOVE 操作直接在
 * rawLocals 上执行，避免 NovaInt 对象的创建。仅当值需要逃逸（方法调用、字段赋值、
 * 返回等）时才通过 {@code safeGet} 惰性装箱为 NovaInt。</p>
 */
final class MirFrame {

    /**
     * 哨兵标记：locals[i] == RAW_INT_MARKER 表示值为 rawLocals[i] 中的原始 int。
     * 使用唯一引用标识，safeGet 通过 == 快速判断。
     */
    static final NovaValue RAW_INT_MARKER = new NovaValue() {
        @Override public String getTypeName() { return "RawInt"; }
        @Override public String getNovaTypeName() { return "Int"; }
        @Override public Object toJavaValue() { return 0; }
        @Override public String toString() { return "<raw-int>"; }
    };

    MirFunction function;
    final NovaValue[] locals;
    /** 原始值并行数组，与 locals[] 同尺寸。当 locals[i] == RAW_INT_MARKER 时此值有效。 */
    final long[] rawLocals;
    int currentBlockId;
    int pc;
    /** TCE 尾递归转循环的迭代次数（异常时由 executeFrame 设置，供 fastCall 合成折叠信息） */
    int tceCount;
    /** reified 类型参数映射（类型参数名 → 实际类型名），null 表示无 reified */
    Map<String, String> reifiedTypes;

    MirFrame(MirFunction function) {
        this.function = function;
        int size = function.getFrameSize();
        this.locals = new NovaValue[size];
        this.rawLocals = new long[size];
        this.currentBlockId = 0;
        this.pc = 0;
    }

    /** 重置帧以复用（帧池化）。仅清零 locals 的前 frameSize 个槽位。 */
    void reset(MirFunction newFunc) {
        this.function = newFunc;
        Arrays.fill(locals, 0, newFunc.getFrameSize(), null);
        this.currentBlockId = 0;
        this.pc = 0;
        this.reifiedTypes = null;
    }

    /**
     * 安全读取寄存器值。自动处理 RAW_INT_MARKER 具化和 null → NovaNull.NULL 转换。
     * <p>所有从 locals 中读取值并传递到外部（环境变量、方法参数、字段赋值、返回值等）
     * 的代码都应使用此方法，而非直接访问 {@code locals[]}。</p>
     */
    NovaValue get(int index) {
        NovaValue val = locals[index];
        if (val == RAW_INT_MARKER) {
            long raw = rawLocals[index];
            if (raw >= Integer.MIN_VALUE && raw <= Integer.MAX_VALUE) {
                return NovaInt.of((int) raw);
            }
            return NovaLong.of(raw);
        }
        return val != null ? val : NovaNull.NULL;
    }

    /**
     * 安全读取寄存器值，null 保持为 null（不转换为 NovaNull.NULL）。
     * 用于需要区分"未初始化"和"显式 null"的场景（如局部变量导出）。
     */
    NovaValue getOrNull(int index) {
        NovaValue val = locals[index];
        if (val == RAW_INT_MARKER) {
            long raw = rawLocals[index];
            if (raw >= Integer.MIN_VALUE && raw <= Integer.MAX_VALUE) {
                return NovaInt.of((int) raw);
            }
            return NovaLong.of(raw);
        }
        return val;
    }
}
