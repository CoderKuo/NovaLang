package com.novalang.runtime.json.fastjson2;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.novalang.runtime.stdlib.spi.JsonProvider;

/**
 * FastJSON2 JSON 提供者。
 */
public final class FastJson2Provider implements JsonProvider {

    @Override public String name() { return "fastjson2"; }
    @Override public int priority() { return 10; }

    @Override
    public Object parse(String text) {
        return JSON.parse(text);
    }

    @Override
    public String stringify(Object value) {
        return JSON.toJSONString(value);
    }

    @Override
    public String stringifyPretty(Object value, int indent) {
        return JSON.toJSONString(value, JSONWriter.Feature.PrettyFormat);
    }
}
