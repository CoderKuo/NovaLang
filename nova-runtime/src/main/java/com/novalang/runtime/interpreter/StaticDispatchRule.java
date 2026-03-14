package com.novalang.runtime.interpreter;

import com.novalang.runtime.NovaValue;

@FunctionalInterface
interface StaticDispatchRule {
    NovaValue tryDispatch(StaticCall call);
}
