package nova.runtime;

/**
 * Nova Char 值
 */
public final class NovaChar extends AbstractNovaValue {

    // ASCII 缓存
    private static final NovaChar[] CACHE = new NovaChar[128];
    static {
        for (int i = 0; i < CACHE.length; i++) {
            CACHE[i] = new NovaChar((char) i);
        }
    }

    /** 获取 NovaChar 实例，ASCII 范围从缓存取 */
    public static NovaChar of(char value) {
        if (value < 128) return CACHE[value];
        return new NovaChar(value);
    }

    private final char value;

    public NovaChar(char value) {
        this.value = value;
    }

    public char getValue() {
        return value;
    }

    @Override
    public String getTypeName() {
        return "Char";
    }

    @Override
    public String getNovaTypeName() {
        return "Char";
    }

    @Override
    public Object toJavaValue() {
        return value;
    }

    @Override
    public int asInt() {
        return value;
    }

    @Override
    public String asString() {
        return String.valueOf(value);
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }

    private String escapeChar(char c) {
        switch (c) {
            case '\n': return "\\n";
            case '\r': return "\\r";
            case '\t': return "\\t";
            case '\\': return "\\\\";
            case '\'': return "\\'";
            case '\0': return "\\0";
            default:
                if (c < 32 || c > 126) {
                    return String.format("\\u%04x", (int) c);
                }
                return String.valueOf(c);
        }
    }

    @Override
    public boolean equals(NovaValue other) {
        if (other == null) return false;
        if (other instanceof NovaChar) {
            return this.value == ((NovaChar) other).value;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Character.hashCode(value);
    }

    // ============ 操作 ============

    public NovaChar increment() {
        return new NovaChar((char) (value + 1));
    }

    public NovaChar decrement() {
        return new NovaChar((char) (value - 1));
    }

    public boolean isDigit() {
        return Character.isDigit(value);
    }

    public boolean isLetter() {
        return Character.isLetter(value);
    }

    public boolean isWhitespace() {
        return Character.isWhitespace(value);
    }

    public NovaChar toUpperCase() {
        return new NovaChar(Character.toUpperCase(value));
    }

    public NovaChar toLowerCase() {
        return new NovaChar(Character.toLowerCase(value));
    }
}
