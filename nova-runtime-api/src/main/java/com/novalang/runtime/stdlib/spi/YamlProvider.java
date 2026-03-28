package com.novalang.runtime.stdlib.spi;

/**
 * YAML 序列化/反序列化提供者接口。
 *
 * <p>通过 Java ServiceLoader 自动发现。优先级高的实现优先使用。</p>
 */
public interface YamlProvider {

    String name();

    int priority();

    /** 解析 YAML 文本为 Map/List/String/Number/Boolean/null */
    Object parse(String text);

    /** 序列化为 YAML 文本 */
    String stringify(Object value);
}
