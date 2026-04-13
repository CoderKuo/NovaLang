package com.novalang.runtime.interpreter.stdlib;

import com.novalang.runtime.NovaErrors;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * nova.crypto 模块 — 编译模式运行时实现。
 * <p>MD5/SHA 哈希 + UUID 生成，全部基于 JDK 标准库。</p>
 */
public final class StdlibCryptoCompiled {

    private StdlibCryptoCompiled() {}

    public static Object md5(Object text) {
        return hash("MD5", str(text));
    }

    public static Object sha1(Object text) {
        return hash("SHA-1", str(text));
    }

    public static Object sha256(Object text) {
        return hash("SHA-256", str(text));
    }

    public static Object uuid() {
        return UUID.randomUUID().toString();
    }

    public static Object uuidFromString(Object text) {
        return UUID.nameUUIDFromBytes(str(text).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** 无连字符 UUID */
    public static Object simpleUUID() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 雪花 ID（基于时间戳 + 机器标识 + 序列号） */
    public static Object snowflakeId() {
        return SnowflakeIdGen.INSTANCE.nextId();
    }

    /** NanoID — URL 友好短 ID */
    public static Object nanoId(Object length) {
        int len = ((Number) length).intValue();
        java.security.SecureRandom random = new java.security.SecureRandom();
        char[] alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_".toCharArray();
        char[] result = new char[len];
        for (int i = 0; i < len; i++) {
            result[i] = alphabet[random.nextInt(alphabet.length)];
        }
        return new String(result);
    }

    // ============ 雪花 ID 生成器 ============

    private static class SnowflakeIdGen {
        static final SnowflakeIdGen INSTANCE = new SnowflakeIdGen(1, 1);
        private static final long EPOCH = 1077206400000L;
        private static final long WORKER_BITS = 5, DC_BITS = 5, SEQ_BITS = 12;
        private static final long MAX_SEQ = (1L << SEQ_BITS) - 1;
        private final long workerId, datacenterId;
        private long sequence = 0, lastTimestamp = -1;

        SnowflakeIdGen(long workerId, long datacenterId) {
            this.workerId = workerId;
            this.datacenterId = datacenterId;
        }

        synchronized long nextId() {
            long ts = System.currentTimeMillis();
            if (ts == lastTimestamp) {
                sequence = (sequence + 1) & MAX_SEQ;
                if (sequence == 0) { while (ts <= lastTimestamp) ts = System.currentTimeMillis(); }
            } else {
                sequence = 0;
            }
            lastTimestamp = ts;
            return ((ts - EPOCH) << (WORKER_BITS + DC_BITS + SEQ_BITS))
                    | (datacenterId << (WORKER_BITS + SEQ_BITS))
                    | (workerId << SEQ_BITS)
                    | sequence;
        }
    }

    private static String hash(String algorithm, String input) {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw NovaErrors.wrap("哈希算法不可用: " + algorithm, e);
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
