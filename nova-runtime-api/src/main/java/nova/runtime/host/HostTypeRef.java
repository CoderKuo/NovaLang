package nova.runtime.host;

import java.util.Objects;

public final class HostTypeRef {
    private final String displayName;

    private HostTypeRef(String displayName) {
        this.displayName = displayName;
    }

    public static HostTypeRef of(String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) {
            throw new IllegalArgumentException("displayName must not be empty");
        }
        return new HostTypeRef(displayName.trim());
    }

    public String displayName() {
        return displayName;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof HostTypeRef)) return false;
        HostTypeRef that = (HostTypeRef) other;
        return Objects.equals(displayName, that.displayName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(displayName);
    }

    @Override
    public String toString() {
        return displayName;
    }
}
