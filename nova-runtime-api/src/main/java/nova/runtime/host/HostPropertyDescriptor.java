package nova.runtime.host;

import java.util.List;

public final class HostPropertyDescriptor extends HostSymbolDescriptor {
    private final HostTypeRef type;
    private final boolean mutable;

    public HostPropertyDescriptor(String name,
                                  HostTypeRef type,
                                  boolean mutable,
                                  String documentation,
                                  String deprecatedMessage,
                                  List<String> examples) {
        super(name, HostSymbolKind.PROPERTY, documentation, deprecatedMessage, examples);
        this.type = type != null ? type : HostTypes.ANY;
        this.mutable = mutable;
    }

    public HostTypeRef getType() {
        return type;
    }

    public boolean isMutable() {
        return mutable;
    }
}
