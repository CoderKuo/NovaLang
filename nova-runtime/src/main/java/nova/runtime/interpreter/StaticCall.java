package nova.runtime.interpreter;

import nova.runtime.NovaValue;

import java.util.List;

final class StaticCall {
    final String owner;
    final String methodName;
    final List<NovaValue> args;

    StaticCall(String owner, String methodName, List<NovaValue> args) {
        this.owner = owner;
        this.methodName = methodName;
        this.args = args;
    }
}
