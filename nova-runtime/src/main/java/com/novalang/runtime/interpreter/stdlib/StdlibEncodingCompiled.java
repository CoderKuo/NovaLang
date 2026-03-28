package com.novalang.runtime.interpreter.stdlib;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * nova.encoding 模块 — 编译模式运行时实现。
 * <p>Base64 / URL / Hex 编解码，全部基于 JDK 标准库。</p>
 */
public final class StdlibEncodingCompiled {

    private StdlibEncodingCompiled() {}

    public static Object base64Encode(Object text) {
        return Base64.getEncoder().encodeToString(str(text).getBytes(StandardCharsets.UTF_8));
    }

    public static Object base64Decode(Object text) {
        return new String(Base64.getDecoder().decode(str(text)), StandardCharsets.UTF_8);
    }

    public static Object urlEncode(Object text) {
        try {
            return URLEncoder.encode(str(text), "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new RuntimeException(e); // UTF-8 always available
        }
    }

    public static Object urlDecode(Object text) {
        try {
            return URLDecoder.decode(str(text), "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    public static Object hexEncode(Object text) {
        byte[] bytes = str(text).getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    public static Object hexDecode(Object hex) {
        String h = str(hex);
        int len = h.length();
        byte[] bytes = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            bytes[i / 2] = (byte) ((Character.digit(h.charAt(i), 16) << 4)
                    + Character.digit(h.charAt(i + 1), 16));
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // ========== Base62 ==========

    private static final char[] BASE62_CHARS =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    /** Base62 编码（纯字母数字，适合邀请码/短链） */
    public static Object base62Encode(Object text) {
        byte[] bytes = str(text).getBytes(StandardCharsets.UTF_8);
        java.math.BigInteger bi = new java.math.BigInteger(1, bytes);
        StringBuilder sb = new StringBuilder();
        java.math.BigInteger base = java.math.BigInteger.valueOf(62);
        while (bi.compareTo(java.math.BigInteger.ZERO) > 0) {
            java.math.BigInteger[] divmod = bi.divideAndRemainder(base);
            sb.append(BASE62_CHARS[divmod[1].intValue()]);
            bi = divmod[0];
        }
        // 前导零字节 → '0' 字符
        for (byte b : bytes) {
            if (b == 0) sb.append('0');
            else break;
        }
        return sb.reverse().toString();
    }

    /** Base62 解码 */
    public static Object base62Decode(Object encoded) {
        String s = str(encoded);
        java.math.BigInteger bi = java.math.BigInteger.ZERO;
        java.math.BigInteger base = java.math.BigInteger.valueOf(62);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int idx;
            if (c >= '0' && c <= '9') idx = c - '0';
            else if (c >= 'A' && c <= 'Z') idx = c - 'A' + 10;
            else if (c >= 'a' && c <= 'z') idx = c - 'a' + 36;
            else throw new RuntimeException("Invalid Base62 character: " + c);
            bi = bi.multiply(base).add(java.math.BigInteger.valueOf(idx));
        }
        byte[] bytes = bi.toByteArray();
        // BigInteger 可能在前面加 0x00 符号位
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            bytes = trimmed;
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
