package com.novalang.runtime.yaml.snakeyaml;

import com.novalang.runtime.stdlib.spi.YamlProvider;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * SnakeYAML YAML 提供者。
 */
public final class SnakeYamlProvider implements YamlProvider {

    @Override public String name() { return "snakeyaml"; }
    @Override public int priority() { return 10; }

    @Override
    public Object parse(String text) {
        Yaml yaml = new Yaml();
        return yaml.load(text);
    }

    @Override
    public String stringify(Object value) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        Yaml yaml = new Yaml(options);
        return yaml.dump(value);
    }
}
