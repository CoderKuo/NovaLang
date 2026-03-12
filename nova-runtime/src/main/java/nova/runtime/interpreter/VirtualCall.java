package nova.runtime.interpreter;

import nova.runtime.NovaValue;

import java.util.List;

final class VirtualCall {
    final NovaValue receiver;
    final String methodName;
    final String owner;
    final List<NovaValue> args;

    VirtualCall(NovaValue receiver, String methodName, String owner, List<NovaValue> args) {
        this.receiver = receiver;
        this.methodName = methodName;
        this.owner = owner;
        this.args = args;
    }
}
