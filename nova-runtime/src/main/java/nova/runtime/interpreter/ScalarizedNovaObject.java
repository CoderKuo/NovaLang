package nova.runtime.interpreter;

import nova.runtime.AbstractNovaValue;
import nova.runtime.NovaCallable;
import nova.runtime.NovaInt;
import nova.runtime.NovaNull;
import nova.runtime.NovaValue;
import nova.runtime.types.NovaClass;

final class ScalarizedNovaObject extends AbstractNovaValue {

    private final NovaClass novaClass;
    private final int fieldCount;
    private int rawMask;
    private long raw0;
    private long raw1;
    private long raw2;
    private long raw3;
    private NovaValue obj0;
    private NovaValue obj1;
    private NovaValue obj2;
    private NovaValue obj3;

    ScalarizedNovaObject(NovaClass novaClass, int fieldCount) {
        this.novaClass = novaClass;
        this.fieldCount = fieldCount;
    }

    static ScalarizedNovaObject fromOperands(NovaClass novaClass, int fieldCount, MirFrame frame, int[] ops) {
        ScalarizedNovaObject value = new ScalarizedNovaObject(novaClass, fieldCount);
        for (int i = 0; i < ops.length && i < fieldCount; i++) {
            value.setFieldFromFrameOperand(i, frame, ops[i]);
        }
        return value;
    }

    NovaClass getNovaClass() {
        return novaClass;
    }

    NovaValue getFieldByIndex(int index) {
        if ((rawMask & (1 << index)) != 0) {
            return NovaInt.of((int) getRaw(index));
        }
        NovaValue value = getObject(index);
        return value != null ? value : NovaNull.NULL;
    }

    boolean exportFieldToFrame(int index, MirFrame frame, int dest) {
        if ((rawMask & (1 << index)) != 0) {
            frame.rawLocals[dest] = getRaw(index);
            frame.locals[dest] = MirFrame.RAW_INT_MARKER;
            return true;
        }
        NovaValue value = getObject(index);
        frame.locals[dest] = value != null ? value : NovaNull.NULL;
        return true;
    }

    boolean isRawIntFieldByIndex(int index) {
        return (rawMask & (1 << index)) != 0;
    }

    long getRawIntFieldByIndex(int index) {
        return getRaw(index);
    }

    void setFieldByIndex(int index, NovaValue value) {
        rawMask &= ~(1 << index);
        setObject(index, value);
    }

    void setRawIntFieldByIndex(int index, long value) {
        rawMask |= (1 << index);
        setRaw(index, value);
        setObject(index, null);
    }

    private void setFieldFromFrameOperand(int index, MirFrame frame, int operand) {
        if (frame.locals[operand] == MirFrame.RAW_INT_MARKER) {
            setRawIntFieldByIndex(index, frame.rawLocals[operand]);
        } else {
            setFieldByIndex(index, frame.locals[operand]);
        }
    }

    NovaCallable getMethod(String name) {
        return novaClass.findCallableMethod(name);
    }

    NovaObject materialize() {
        NovaObject obj = new NovaObject(novaClass);
        for (int i = 0; i < fieldCount; i++) {
            if ((rawMask & (1 << i)) != 0) {
                obj.setFieldByIndex(i, NovaInt.of((int) getRaw(i)));
            } else {
                obj.setFieldByIndex(i, getObject(i));
            }
        }
        return obj;
    }

    NovaValue getMethodReceiverValue() {
        return this;
    }

    private long getRaw(int index) {
        switch (index) {
            case 0: return raw0;
            case 1: return raw1;
            case 2: return raw2;
            case 3: return raw3;
            default: throw new IllegalArgumentException("Unsupported scalar field index: " + index);
        }
    }

    private void setRaw(int index, long value) {
        switch (index) {
            case 0: raw0 = value; break;
            case 1: raw1 = value; break;
            case 2: raw2 = value; break;
            case 3: raw3 = value; break;
            default: throw new IllegalArgumentException("Unsupported scalar field index: " + index);
        }
    }

    private NovaValue getObject(int index) {
        switch (index) {
            case 0: return obj0;
            case 1: return obj1;
            case 2: return obj2;
            case 3: return obj3;
            default: throw new IllegalArgumentException("Unsupported scalar field index: " + index);
        }
    }

    private void setObject(int index, NovaValue value) {
        switch (index) {
            case 0: obj0 = value; break;
            case 1: obj1 = value; break;
            case 2: obj2 = value; break;
            case 3: obj3 = value; break;
            default: throw new IllegalArgumentException("Unsupported scalar field index: " + index);
        }
    }

    @Override
    public boolean isObject() {
        return true;
    }

    @Override
    public String getTypeName() {
        return novaClass.getName();
    }

    @Override
    public String getNovaTypeName() {
        return novaClass.getName();
    }

    @Override
    public boolean equals(NovaValue other) {
        if (other == this) return true;
        if (other instanceof ScalarizedNovaObject) {
            ScalarizedNovaObject o = (ScalarizedNovaObject) other;
            if (this.novaClass != o.novaClass || this.fieldCount != o.fieldCount) return false;
            for (int i = 0; i < fieldCount; i++) {
                boolean thisRaw = (rawMask & (1 << i)) != 0;
                boolean otherRaw = (o.rawMask & (1 << i)) != 0;
                if (thisRaw && otherRaw) {
                    if (getRaw(i) != o.getRaw(i)) return false;
                } else {
                    NovaValue thisVal = getFieldByIndex(i);
                    NovaValue otherVal = o.getFieldByIndex(i);
                    if (!thisVal.equals(otherVal)) return false;
                }
            }
            return true;
        }
        if (other instanceof NovaObject) {
            NovaObject o = (NovaObject) other;
            if (this.novaClass != o.getNovaClass()) return false;
            for (int i = 0; i < fieldCount; i++) {
                if (!getFieldByIndex(i).equals(o.getFieldByIndex(i))) return false;
            }
            return true;
        }
        return false;
    }

    @Override
    public int hashCode() {
        int result = novaClass.hashCode();
        for (int i = 0; i < fieldCount; i++) {
            if ((rawMask & (1 << i)) != 0) {
                result = 31 * result + Long.hashCode(getRaw(i));
            } else {
                NovaValue val = getObject(i);
                result = 31 * result + (val != null ? val.hashCode() : 0);
            }
        }
        return result;
    }

    @Override
    public Object toJavaValue() {
        return this;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(novaClass.getName()).append('(');
        for (int i = 0; i < fieldCount; i++) {
            if (i > 0) sb.append(", ");
            String[] fieldNames = novaClass.getFieldNames();
            if (fieldNames != null && i < fieldNames.length) {
                sb.append(fieldNames[i]).append('=');
            }
            if ((rawMask & (1 << i)) != 0) {
                sb.append((int) getRaw(i));
            } else {
                NovaValue value = getObject(i);
                sb.append(value != null ? value.asString() : "null");
            }
        }
        sb.append(')');
        return sb.toString();
    }
}
