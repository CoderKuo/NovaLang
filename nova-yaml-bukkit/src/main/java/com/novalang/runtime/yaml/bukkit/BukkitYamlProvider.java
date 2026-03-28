package com.novalang.runtime.yaml.bukkit;

import com.novalang.runtime.stdlib.spi.YamlProvider;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.StringReader;
import java.util.*;

/**
 * Bukkit YAML 提供者 — 使用 Bukkit Configuration API。
 * <p>优先级低于 SnakeYAML（5 vs 10），仅当 SnakeYAML 不可用时使用。</p>
 */
public final class BukkitYamlProvider implements YamlProvider {

    @Override public String name() { return "bukkit"; }
    @Override public int priority() { return 5; }

    @Override
    public Object parse(String text) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(new StringReader(text));
        } catch (Exception e) {
            throw new RuntimeException("YAML parse failed: " + e.getMessage(), e);
        }
        return sectionToMap(config);
    }

    @Override
    public String stringify(Object value) {
        YamlConfiguration config = new YamlConfiguration();
        if (value instanceof Map) {
            mapToSection(config, (Map<?, ?>) value);
        }
        return config.saveToString();
    }

    private static Map<String, Object> sectionToMap(ConfigurationSection section) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object val = section.get(key);
            if (val instanceof ConfigurationSection) {
                map.put(key, sectionToMap((ConfigurationSection) val));
            } else if (val instanceof List) {
                map.put(key, convertList((List<?>) val));
            } else {
                map.put(key, val);
            }
        }
        return map;
    }

    private static List<Object> convertList(List<?> list) {
        List<Object> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof ConfigurationSection) {
                result.add(sectionToMap((ConfigurationSection) item));
            } else if (item instanceof List) {
                result.add(convertList((List<?>) item));
            } else {
                result.add(item);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static void mapToSection(ConfigurationSection section, Map<?, ?> map) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object val = entry.getValue();
            if (val instanceof Map) {
                ConfigurationSection sub = section.createSection(key);
                mapToSection(sub, (Map<?, ?>) val);
            } else {
                section.set(key, val);
            }
        }
    }
}
