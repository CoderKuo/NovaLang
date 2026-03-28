package com.novalang.runtime.interpreter.stdlib;

import com.novalang.runtime.NovaString;
import com.novalang.runtime.types.Environment;
import com.novalang.runtime.interpreter.Interpreter;
import com.novalang.runtime.interpreter.NovaNativeFunction;

/**
 * nova.encoding — Base64 / URL / Hex 编解码（解释器路径）
 */
public final class StdlibEncoding {

    private StdlibEncoding() {}

    public static void register(Environment env, Interpreter interp) {
        env.defineVal("base64Encode", NovaNativeFunction.create("base64Encode",
                v -> NovaString.of((String) StdlibEncodingCompiled.base64Encode(v.asString()))));
        env.defineVal("base64Decode", NovaNativeFunction.create("base64Decode",
                v -> NovaString.of((String) StdlibEncodingCompiled.base64Decode(v.asString()))));
        env.defineVal("urlEncode", NovaNativeFunction.create("urlEncode",
                v -> NovaString.of((String) StdlibEncodingCompiled.urlEncode(v.asString()))));
        env.defineVal("urlDecode", NovaNativeFunction.create("urlDecode",
                v -> NovaString.of((String) StdlibEncodingCompiled.urlDecode(v.asString()))));
        env.defineVal("hexEncode", NovaNativeFunction.create("hexEncode",
                v -> NovaString.of((String) StdlibEncodingCompiled.hexEncode(v.asString()))));
        env.defineVal("hexDecode", NovaNativeFunction.create("hexDecode",
                v -> NovaString.of((String) StdlibEncodingCompiled.hexDecode(v.asString()))));
        env.defineVal("base62Encode", NovaNativeFunction.create("base62Encode",
                v -> NovaString.of((String) StdlibEncodingCompiled.base62Encode(v.asString()))));
        env.defineVal("base62Decode", NovaNativeFunction.create("base62Decode",
                v -> NovaString.of((String) StdlibEncodingCompiled.base62Decode(v.asString()))));
    }
}
