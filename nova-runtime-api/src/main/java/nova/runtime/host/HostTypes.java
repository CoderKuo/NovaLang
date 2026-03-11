package nova.runtime.host;

public final class HostTypes {
    public static final HostTypeRef ANY = HostTypeRef.of("Any");
    public static final HostTypeRef UNIT = HostTypeRef.of("Unit");
    public static final HostTypeRef STRING = HostTypeRef.of("String");
    public static final HostTypeRef INT = HostTypeRef.of("Int");
    public static final HostTypeRef LONG = HostTypeRef.of("Long");
    public static final HostTypeRef DOUBLE = HostTypeRef.of("Double");
    public static final HostTypeRef FLOAT = HostTypeRef.of("Float");
    public static final HostTypeRef BOOLEAN = HostTypeRef.of("Boolean");

    private HostTypes() {}
}
