package com.novalang.ir.pass;

import com.novalang.ir.hir.decl.HirModule;

/**
 * HIR 优化 pass 接口。
 */
public interface HirPass {

    /**
     * Pass 名称（用于日志/调试）。
     */
    String getName();

    /**
     * 对 HIR 模块执行优化。
     */
    HirModule run(HirModule module);
}
