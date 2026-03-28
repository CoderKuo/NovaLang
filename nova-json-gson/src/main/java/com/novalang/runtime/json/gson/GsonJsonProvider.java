package com.novalang.runtime.json.gson;

import com.google.gson.*;
import com.novalang.runtime.stdlib.spi.JsonProvider;

import java.util.*;

/**
 * Gson JSON 提供者。
 */
public final class GsonJsonProvider implements JsonProvider {

    private static final Gson GSON = new GsonBuilder().create();
    private static final Gson GSON_PRETTY = new GsonBuilder().setPrettyPrinting().create();

    @Override public String name() { return "gson"; }
    @Override public int priority() { return 10; }

    @Override
    public Object parse(String text) {
        JsonElement element = JsonParser.parseString(text);
        return toJava(element);
    }

    @Override
    public String stringify(Object value) {
        return GSON.toJson(value);
    }

    @Override
    public String stringifyPretty(Object value, int indent) {
        return GSON_PRETTY.toJson(value);
    }

    private static Object toJava(JsonElement element) {
        if (element == null || element.isJsonNull()) return null;
        if (element.isJsonPrimitive()) {
            JsonPrimitive p = element.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isNumber()) {
                Number n = p.getAsNumber();
                double d = n.doubleValue();
                if (d == Math.floor(d) && !Double.isInfinite(d)) {
                    long l = n.longValue();
                    if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) return (int) l;
                    return l;
                }
                return d;
            }
            return p.getAsString();
        }
        if (element.isJsonArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonElement e : element.getAsJsonArray()) list.add(toJava(e));
            return list;
        }
        if (element.isJsonObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : element.getAsJsonObject().entrySet()) {
                map.put(e.getKey(), toJava(e.getValue()));
            }
            return map;
        }
        return null;
    }
}
