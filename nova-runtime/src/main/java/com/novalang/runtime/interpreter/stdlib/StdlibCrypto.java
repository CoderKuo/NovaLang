package com.novalang.runtime.interpreter.stdlib;

import com.novalang.runtime.NovaString;
import com.novalang.runtime.types.Environment;
import com.novalang.runtime.interpreter.Interpreter;
import com.novalang.runtime.interpreter.NovaNativeFunction;

/**
 * nova.crypto — MD5/SHA 哈希 + UUID 生成（解释器路径）
 */
public final class StdlibCrypto {

    private StdlibCrypto() {}

    public static void register(Environment env, Interpreter interp) {
        env.defineVal("md5", NovaNativeFunction.create("md5",
                v -> NovaString.of((String) StdlibCryptoCompiled.md5(v.asString()))));
        env.defineVal("sha1", NovaNativeFunction.create("sha1",
                v -> NovaString.of((String) StdlibCryptoCompiled.sha1(v.asString()))));
        env.defineVal("sha256", NovaNativeFunction.create("sha256",
                v -> NovaString.of((String) StdlibCryptoCompiled.sha256(v.asString()))));
        env.defineVal("uuid", NovaNativeFunction.create("uuid",
                () -> NovaString.of((String) StdlibCryptoCompiled.uuid())));
        env.defineVal("uuidFromString", NovaNativeFunction.create("uuidFromString",
                v -> NovaString.of((String) StdlibCryptoCompiled.uuidFromString(v.asString()))));
        env.defineVal("simpleUUID", NovaNativeFunction.create("simpleUUID",
                () -> NovaString.of((String) StdlibCryptoCompiled.simpleUUID())));
        env.defineVal("snowflakeId", NovaNativeFunction.create("snowflakeId",
                () -> com.novalang.runtime.NovaLong.of((Long) StdlibCryptoCompiled.snowflakeId())));
        env.defineVal("nanoId", NovaNativeFunction.create("nanoId",
                v -> NovaString.of((String) StdlibCryptoCompiled.nanoId(v.toJavaValue()))));
    }
}
