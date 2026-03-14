package com.novalang.runtime.host;

@FunctionalInterface
public interface HostFunctionInvoker {
    Object invoke(Object... args) throws Exception;
}
