package com.novalang.runtime.interpreter;

import com.novalang.runtime.NovaValue;

@FunctionalInterface
interface VirtualDispatchRule {
    NovaValue tryDispatch(VirtualCall call);
}
