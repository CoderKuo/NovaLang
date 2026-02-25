package nova.runtime.stdlib;

/**
 * 字符串相关的 stdlib 接收者 Lambda 函数注册。
 */
final class Strings {

    private Strings() {}

    static void register() {
        // buildString { append("Hello"); append(" World") } → "Hello World"
        StdlibRegistry.register(new StdlibRegistry.ReceiverLambdaInfo(
            "buildString",
            "java/lang/StringBuilder",
            "Ljava/lang/String;",
            "toString",
            consumer -> {
                StringBuilder sb = new StringBuilder();
                consumer.accept(sb);
                return sb.toString();
            }
        ));
    }
}
