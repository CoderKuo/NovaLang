package com.novalang.runtime.host;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HostBindingRegistry 测试")
class HostBindingRegistryTest {

    @Test
    @DisplayName("命名空间解析会合并 default 与 extends")
    void resolveNamespaceMergesDefaultAndParents() {
        HostBindingRegistry registry = HostBindingRegistry.builder()
                .globalFunction("log", function -> function
                        .param("message", HostTypes.STRING)
                        .returns(HostTypes.UNIT)
                        .doc("输出日志"))
                .namespace("default", namespace -> namespace
                        .variable("player", variable -> variable
                                .type("Player")
                                .readonly()))
                .namespace("rewardBase", namespace -> namespace
                        .function("grantBase", function -> function
                                .param("count", HostTypes.INT)
                                .returns(HostTypes.UNIT)))
                .namespace("reward", namespace -> namespace
                        .extendsNamespace("rewardBase")
                        .function("giveItem", function -> function
                                .param("itemId", HostTypes.STRING)
                                .param("count", HostTypes.INT)
                                .returns(HostTypes.UNIT)))
                .build();

        HostNamespaceDescriptor resolved = registry.resolveNamespace("reward");

        assertThat(resolved.getGlobals())
                .extracting(HostSymbolDescriptor::getName)
                .containsExactly("log", "player", "grantBase", "giveItem");
    }

    @Test
    @DisplayName("JSON 导出包含 globals 与 namespaces")
    void jsonWriterExportsExpectedShape() {
        HostBindingRegistry registry = HostBindingRegistry.builder()
                .globalVariable("player", variable -> variable
                        .type("Player")
                        .doc("当前玩家"))
                .namespace("reward", namespace -> namespace
                        .function("giveItem", function -> function
                                .param("itemId", HostTypes.STRING)
                                .param("count", HostTypes.INT)
                                .returns(HostTypes.UNIT)
                                .doc("发放奖励")))
                .build();

        String json = HostBindingJsonWriter.toJson(registry);

        assertThat(json).contains("\"version\": 1");
        assertThat(json).contains("\"globals\"");
        assertThat(json).contains("\"player\"");
        assertThat(json).contains("\"namespaces\"");
        assertThat(json).contains("\"reward\"");
        assertThat(json).contains("\"giveItem\"");
    }
}
