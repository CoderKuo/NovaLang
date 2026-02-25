package com.novalang.compiler.compiler;

import java.util.Map;

/**
 * 编译器统一接口。
 *
 * <p>{@code NovaCompiler}（AST 直编）和 {@code NovaIrCompiler}（IR 管道）
 * 均实现此接口，供 {@link IncrementalCompiler} 等工具类使用。</p>
 */
public interface NovaCompilerApi {

    /**
     * 编译源码为字节码。
     *
     * @param source   源码字符串
     * @param fileName 文件名（用于错误信息和类名生成）
     * @return 类名 → 字节码映射
     */
    Map<String, byte[]> compile(String source, String fileName);
}
