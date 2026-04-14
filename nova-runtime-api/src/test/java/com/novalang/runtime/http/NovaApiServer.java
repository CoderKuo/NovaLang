package com.novalang.runtime.http;

import java.util.concurrent.atomic.AtomicInteger;

public final class NovaApiServer {

    private static final AtomicInteger START_CALLS = new AtomicInteger();

    private NovaApiServer() {
    }

    public static Object startDefault() {
        START_CALLS.incrementAndGet();
        return new Object();
    }

    public static int startCalls() {
        return START_CALLS.get();
    }

    public static void reset() {
        START_CALLS.set(0);
    }
}
