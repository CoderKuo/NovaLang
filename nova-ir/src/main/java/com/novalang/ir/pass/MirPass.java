package com.novalang.ir.pass;

import com.novalang.ir.mir.MirModule;

/**
 * MIR 优化 pass 接口。
 */
public interface MirPass {

    /**
     * Pass 名称。
     */
    String getName();

    /**
     * 对 MIR 模块执行优化。
     */
    MirModule run(MirModule module);
}
