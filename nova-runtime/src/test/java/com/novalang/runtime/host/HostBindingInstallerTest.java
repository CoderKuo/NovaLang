package com.novalang.runtime.host;

import com.novalang.runtime.Nova;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HostBindingInstaller 测试")
class HostBindingInstallerTest {

    @Test
    @DisplayName("安装默认命名空间变量与函数")
    void installDefaultNamespace() {
        HostBindingRegistry registry = HostBindingRegistry.builder()
                .globalVariable("score", variable -> variable
                        .type(HostTypes.INT)
                        .mutable()
                        .value(42))
                .globalFunction("add", function -> function
                        .param("a", HostTypes.INT)
                        .param("b", HostTypes.INT)
                        .returns(HostTypes.INT)
                        .invoke(args -> ((Number) args[0]).intValue() + ((Number) args[1]).intValue()))
                .build();

        Nova nova = new Nova();
        HostBindingInstaller.install(nova, registry);

        assertThat(nova.get("score")).isEqualTo(42);
        assertThat(nova.call("add", 2, 3)).isEqualTo(5);
    }

    @Test
    @DisplayName("安装指定命名空间会合并 default")
    void installSpecificNamespace() {
        HostBindingRegistry registry = HostBindingRegistry.builder()
                .namespace("default", namespace -> namespace
                        .variable("playerName", variable -> variable
                                .type(HostTypes.STRING)
                                .value("Nova")))
                .namespace("reward", namespace -> namespace
                        .extendsNamespace("default")
                        .function("giveMoney", function -> function
                                .param("amount", HostTypes.INT)
                                .returns(HostTypes.UNIT)
                                .invoke(args -> null)))
                .build();

        Nova nova = new Nova();
        HostBindingInstaller.installNamespace(nova, registry, "reward");

        assertThat(nova.get("playerName")).isEqualTo("Nova");
        assertThat(nova.get("giveMoney")).isNotNull();
    }
}
