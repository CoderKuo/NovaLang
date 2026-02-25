package com.novalang.ir.mir;

/**
 * MIR 局部变量。
 */
public class MirLocal {

    private final int index;
    private String name;
    private final MirType type;

    public MirLocal(int index, String name, MirType type) {
        this.index = index;
        this.name = name;
        this.type = type;
    }

    public int getIndex() { return index; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public MirType getType() { return type; }

    @Override
    public String toString() {
        return "%" + index + ":" + name + "(" + type + ")";
    }
}
