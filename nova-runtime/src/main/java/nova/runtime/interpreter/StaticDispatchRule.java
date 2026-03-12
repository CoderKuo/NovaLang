package nova.runtime.interpreter;

import nova.runtime.NovaValue;

@FunctionalInterface
interface StaticDispatchRule {
    NovaValue tryDispatch(StaticCall call);
}
