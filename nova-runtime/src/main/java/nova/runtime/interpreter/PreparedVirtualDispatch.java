package nova.runtime.interpreter;

import nova.runtime.NovaValue;

final class PreparedVirtualDispatch {
    private final NovaValue receiver;
    private final NovaValue immediateResult;

    private PreparedVirtualDispatch(NovaValue receiver, NovaValue immediateResult) {
        this.receiver = receiver;
        this.immediateResult = immediateResult;
    }

    static PreparedVirtualDispatch continueWith(NovaValue receiver) {
        return new PreparedVirtualDispatch(receiver, null);
    }

    static PreparedVirtualDispatch shortCircuit(NovaValue result) {
        return new PreparedVirtualDispatch(null, result);
    }

    boolean hasImmediateResult() {
        return immediateResult != null;
    }

    NovaValue getReceiver() {
        return receiver;
    }

    NovaValue getImmediateResult() {
        return immediateResult;
    }
}
