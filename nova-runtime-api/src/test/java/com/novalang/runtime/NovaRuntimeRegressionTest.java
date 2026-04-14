package com.novalang.runtime;

import com.novalang.runtime.http.NovaApiServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NovaRuntime regression coverage")
class NovaRuntimeRegressionTest {

    @BeforeEach
    void resetRuntimeState() throws Exception {
        clearGlobalBridge();
        resetSharedSingleton();
        NovaApiServer.reset();
    }

    @AfterEach
    void cleanupRuntimeState() throws Exception {
        clearGlobalBridge();
        resetSharedSingleton();
        NovaApiServer.reset();
    }

    @Test
    @DisplayName("clearAll should not delete a newer bridge entry owned by another runtime")
    void clearAllShouldPreserveForeignBridgeEntries() {
        NovaRuntime first = NovaRuntime.create();
        NovaRuntime second = NovaRuntime.create();

        first.register("sharedName", (Function0<Object>) () -> "first");
        second.register("sharedName", (Function0<Object>) () -> "second");

        assertThat(NovaRuntime.callGlobal("sharedName")).isEqualTo("second");

        first.clearAll();

        assertThat(NovaRuntime.callGlobal("sharedName"))
                .as("clearing one runtime should not wipe out another runtime's later registration")
                .isEqualTo("second");
    }

    @Test
    @DisplayName("unregistering the latest namespace should restore the previous short-name binding")
    void unregisterNamespaceShouldRestorePreviousShortNameBinding() {
        NovaRuntime runtime = NovaRuntime.create();

        runtime.register("hello", (Function0<Object>) () -> "from A", "nsA");
        runtime.register("hello", (Function0<Object>) () -> "from B", "nsB");

        assertThat(runtime.lookup("nsA", "hello")).isNotNull();
        assertThat(runtime.lookup("hello")).isNotNull();
        assertThat(runtime.lookup("hello").invoke()).isEqualTo("from B");

        runtime.unregisterNamespace("nsB");

        assertThat(runtime.lookup("nsA", "hello"))
                .as("the remaining namespace entry should still exist")
                .isNotNull();
        assertThat(runtime.lookup("hello"))
                .as("short-name lookup should fall back to the remaining namespace registration")
                .isNotNull();
        assertThat(runtime.lookup("hello").invoke()).isEqualTo("from A");
    }

    @Test
    @DisplayName("call should reach bridge entries that lookup can already resolve")
    void callShouldUseGlobalBridgeEntries() throws Exception {
        NovaRuntime producer = NovaRuntime.create();
        NovaRuntime consumer = NovaRuntime.create();

        producer.register("bridgeOnly", (Function0<Object>) () -> "ok");

        assertThat(consumer.lookup("bridgeOnly"))
                .as("lookup already sees the bridged entry")
                .isNotNull();

        NovaValue result = consumer.call("bridgeOnly");

        assertThat(result.toJava(String.class)).isEqualTo("ok");
    }

    @Test
    @DisplayName("shared should not auto-start the HTTP API server as a side effect")
    void sharedShouldNotAutoStartHttpServer() {
        assertThat(NovaApiServer.startCalls()).isZero();

        NovaRuntime.shared();

        assertThat(NovaApiServer.startCalls())
                .as("obtaining the shared runtime should be side-effect free")
                .isZero();
    }

    private static void resetSharedSingleton() throws Exception {
        Field sharedField = NovaRuntime.class.getDeclaredField("SHARED");
        sharedField.setAccessible(true);
        sharedField.set(null, null);
    }

    private static void clearGlobalBridge() throws Exception {
        Properties properties = System.getProperties();
        properties.remove(readStaticString("GLOBAL_REGISTRY_KEY"));
        properties.remove(readStaticString("GLOBAL_NS_KEY"));
    }

    private static String readStaticString(String fieldName) throws Exception {
        Field field = NovaRuntime.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (String) field.get(null);
    }
}
