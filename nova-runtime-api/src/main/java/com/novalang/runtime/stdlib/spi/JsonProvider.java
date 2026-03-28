package com.novalang.runtime.stdlib.spi;

/**
 * JSON 序列化/反序列化提供者接口。
 *
 * <p>通过 Java ServiceLoader 自动发现。优先级高的实现优先使用。</p>
 * <p>内置实现优先级为 0，第三方（Gson/FastJSON2）为 10。</p>
 */
public interface JsonProvider {

    /** 提供者名称（"builtin"、"gson"、"fastjson2"） */
    String name();

    /** 优先级，越高越优先。内置=0，第三方=10 */
    int priority();

    /** 解析 JSON 文本为 Map/List/String/Number/Boolean/null */
    Object parse(String text);

    /** 序列化为紧凑 JSON */
    String stringify(Object value);

    /** 序列化为格式化 JSON */
    String stringifyPretty(Object value, int indent);
}
