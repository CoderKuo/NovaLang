package nova.runtime.host;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

public final class HostObjectDescriptor extends HostSymbolDescriptor {
    private final HostTypeRef type;
    private final Object value;
    private final Supplier<?> supplier;
    private final List<HostSymbolDescriptor> members;

    public HostObjectDescriptor(String name,
                                HostTypeRef type,
                                String documentation,
                                String deprecatedMessage,
                                List<String> examples,
                                Object value,
                                Supplier<?> supplier,
                                List<HostSymbolDescriptor> members) {
        super(name, HostSymbolKind.OBJECT, documentation, deprecatedMessage, examples);
        this.type = type != null ? type : HostTypes.ANY;
        this.value = value;
        this.supplier = supplier;
        this.members = Collections.unmodifiableList(new ArrayList<HostSymbolDescriptor>(members != null ? members : Collections.<HostSymbolDescriptor>emptyList()));
    }

    public HostTypeRef getType() {
        return type;
    }

    public Object getValue() {
        return value;
    }

    public Supplier<?> getSupplier() {
        return supplier;
    }

    public List<HostSymbolDescriptor> getMembers() {
        return members;
    }
}
