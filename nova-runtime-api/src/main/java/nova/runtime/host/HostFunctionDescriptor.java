package nova.runtime.host;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class HostFunctionDescriptor extends HostSymbolDescriptor {
    private final List<HostParameterDescriptor> parameters;
    private final HostTypeRef returnType;
    private final HostFunctionInvoker invoker;

    public HostFunctionDescriptor(String name,
                                  List<HostParameterDescriptor> parameters,
                                  HostTypeRef returnType,
                                  String documentation,
                                  String deprecatedMessage,
                                  List<String> examples,
                                  HostFunctionInvoker invoker) {
        super(name, HostSymbolKind.FUNCTION, documentation, deprecatedMessage, examples);
        this.parameters = Collections.unmodifiableList(new ArrayList<HostParameterDescriptor>(parameters != null ? parameters : Collections.<HostParameterDescriptor>emptyList()));
        this.returnType = returnType != null ? returnType : HostTypes.UNIT;
        this.invoker = invoker;
    }

    public List<HostParameterDescriptor> getParameters() {
        return parameters;
    }

    public HostTypeRef getReturnType() {
        return returnType;
    }

    public HostFunctionInvoker getInvoker() {
        return invoker;
    }

    public boolean isVararg() {
        return !parameters.isEmpty() && parameters.get(parameters.size() - 1).isVararg();
    }
}
