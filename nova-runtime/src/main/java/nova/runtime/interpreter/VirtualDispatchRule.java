package nova.runtime.interpreter;

import nova.runtime.NovaValue;

@FunctionalInterface
interface VirtualDispatchRule {
    NovaValue tryDispatch(VirtualCall call);
}
