package nova.runtime.interpreter;
import nova.runtime.*;

/**
 * 控制流异常
 *
 * <p>用于实现 return、break、continue 等控制流语句。
 * 这些不是真正的错误，而是用于跳出正常执行流程的机制。</p>
 */
public class ControlFlow extends RuntimeException {

    /**
     * 控制流类型
     */
    public enum Type {
        RETURN,
        BREAK,
        CONTINUE,
        THROW
    }

    private final Type type;
    private final NovaValue value;
    private final String label;

    private ControlFlow(Type type, NovaValue value, String label) {
        super(null, null, false, false);  // 禁用堆栈跟踪以提高性能
        this.type = type;
        this.value = value;
        this.label = label;
    }

    public Type getType() {
        return type;
    }

    public NovaValue getValue() {
        return value;
    }

    public String getLabel() {
        return label;
    }

    public boolean hasLabel() {
        return label != null;
    }

    public boolean matchesLabel(String targetLabel) {
        if (label == null) return true;  // 无标签匹配任意
        return label.equals(targetLabel);
    }

    // ============ 工厂方法 ============

    public static ControlFlow returnValue(NovaValue value) {
        return new ControlFlow(Type.RETURN, value, null);
    }

    public static ControlFlow returnVoid() {
        return new ControlFlow(Type.RETURN, NovaNull.UNIT, null);
    }

    public static ControlFlow returnWithLabel(NovaValue value, String label) {
        return new ControlFlow(Type.RETURN, value, label);
    }

    public static ControlFlow breakLoop() {
        return new ControlFlow(Type.BREAK, null, null);
    }

    public static ControlFlow breakLoop(String label) {
        return new ControlFlow(Type.BREAK, null, label);
    }

    public static ControlFlow continueLoop() {
        return new ControlFlow(Type.CONTINUE, null, null);
    }

    public static ControlFlow continueLoop(String label) {
        return new ControlFlow(Type.CONTINUE, null, label);
    }

    public static ControlFlow throwException(NovaValue exception) {
        return new ControlFlow(Type.THROW, exception, null);
    }
}
