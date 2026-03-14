package com.novalang.runtime.interpreter;

import com.novalang.runtime.NovaValue;

import java.util.Arrays;
import java.util.List;

final class MemoKey {

    private final NovaValue[] args;
    private final int hash;

    private MemoKey(NovaValue[] args) {
        this.args = args;
        this.hash = Arrays.hashCode(args);
    }

    static MemoKey ofArray(NovaValue[] args) {
        return new MemoKey(args.clone());
    }

    static MemoKey ofList(List<NovaValue> args) {
        return new MemoKey(args.toArray(new NovaValue[0]));
    }

    static MemoKey of1(NovaValue a0) {
        return new MemoKey(new NovaValue[]{a0});
    }

    static MemoKey of2(NovaValue a0, NovaValue a1) {
        return new MemoKey(new NovaValue[]{a0, a1});
    }

    static MemoKey of3(NovaValue a0, NovaValue a1, NovaValue a2) {
        return new MemoKey(new NovaValue[]{a0, a1, a2});
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof MemoKey)) {
            return false;
        }
        MemoKey other = (MemoKey) obj;
        return Arrays.equals(args, other.args);
    }

    @Override
    public int hashCode() {
        return hash;
    }
}
