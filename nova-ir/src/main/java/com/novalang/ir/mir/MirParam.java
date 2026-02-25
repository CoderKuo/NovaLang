package com.novalang.ir.mir;

/**
 * MIR 函数参数。
 */
public class MirParam {

    private final String name;
    private final MirType type;
    private final boolean hasDefault;

    public MirParam(String name, MirType type) {
        this(name, type, false);
    }

    public MirParam(String name, MirType type, boolean hasDefault) {
        this.name = name;
        this.type = type;
        this.hasDefault = hasDefault;
    }

    public String getName() { return name; }
    public MirType getType() { return type; }
    public boolean hasDefault() { return hasDefault; }
}
