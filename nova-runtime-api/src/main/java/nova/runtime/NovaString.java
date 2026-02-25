package nova.runtime;

import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nova String 值
 */
public final class NovaString extends NovaValue implements Iterable<NovaValue> {

    /** 空字符串常量 */
    public static final NovaString EMPTY = new NovaString("");

    /** 短字符串驻留池（<64 字符） - 使用 ConcurrentHashMap + 大小限制 */
    private static final ConcurrentHashMap<String, NovaString> INTERN_POOL = new ConcurrentHashMap<>();
    private static final int INTERN_MAX_LENGTH = 64;
    private static final int INTERN_MAX_SIZE = 4096;

    private final String value;

    private NovaString(String value) {
        this.value = value != null ? value : "";
    }

    /**
     * 工厂方法：短字符串驻留，长字符串直接创建
     */
    public static NovaString of(String value) {
        if (value == null || value.isEmpty()) return EMPTY;
        if (value.length() <= INTERN_MAX_LENGTH) {
            NovaString cached = INTERN_POOL.get(value);
            if (cached != null) return cached;
            NovaString ns = new NovaString(value);
            // 超过限制时跳过缓存，避免无限增长
            if (INTERN_POOL.size() < INTERN_MAX_SIZE) {
                NovaString existing = INTERN_POOL.putIfAbsent(value, ns);
                return existing != null ? existing : ns;
            }
            return ns;
        }
        return new NovaString(value);
    }

    /**
     * 获取 intern 池统计信息
     */
    public static String getInternPoolStats() {
        return "NovaString.INTERN_POOL: size=" + INTERN_POOL.size() + ", maxSize=" + INTERN_MAX_SIZE;
    }

    public String getValue() {
        return value;
    }

    @Override
    public String getTypeName() {
        return "String";
    }

    @Override
    public String getNovaTypeName() {
        return "String";
    }

    @Override
    public Object toJavaValue() {
        return value;
    }

    @Override
    public boolean isTruthy() {
        return !value.isEmpty();
    }

    @Override
    public boolean isString() {
        return true;
    }

    @Override
    public String asString() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }

    @Override
    public boolean equals(NovaValue other) {
        if (other == null) return false;
        if (other instanceof NovaString) {
            return this.value.equals(((NovaString) other).value);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public Iterator<NovaValue> iterator() {
        return new Iterator<NovaValue>() {
            private int index = 0;

            @Override
            public boolean hasNext() {
                return index < value.length();
            }

            @Override
            public NovaValue next() {
                if (!hasNext()) {
                    throw new java.util.NoSuchElementException();
                }
                return NovaChar.of(value.charAt(index++));
            }
        };
    }

    // ============ 字符串操作 ============

    public int length() {
        return value.length();
    }

    public boolean isEmpty() {
        return value.isEmpty();
    }

    public boolean isBlank() {
        return value.trim().isEmpty();
    }

    public NovaString concat(NovaString other) {
        return new NovaString(this.value + other.value);
    }

    public NovaString concat(NovaValue other) {
        return new NovaString(this.value + other.asString());
    }

    public NovaChar charAt(int index) {
        if (index < 0 || index >= value.length()) {
            throw new NovaException("String index out of bounds: " + index);
        }
        return NovaChar.of(value.charAt(index));
    }

    public NovaString substring(int start) {
        return new NovaString(value.substring(start));
    }

    public NovaString substring(int start, int end) {
        return new NovaString(value.substring(start, end));
    }

    public int indexOf(String str) {
        return value.indexOf(str);
    }

    public int indexOf(String str, int fromIndex) {
        return value.indexOf(str, fromIndex);
    }

    public int lastIndexOf(String str) {
        return value.lastIndexOf(str);
    }

    public boolean contains(String str) {
        return value.contains(str);
    }

    public boolean startsWith(String prefix) {
        return value.startsWith(prefix);
    }

    public boolean endsWith(String suffix) {
        return value.endsWith(suffix);
    }

    public NovaString toUpperCase() {
        return new NovaString(value.toUpperCase());
    }

    public NovaString toLowerCase() {
        return new NovaString(value.toLowerCase());
    }

    public NovaString trim() {
        return new NovaString(value.trim());
    }

    public NovaString replace(String target, String replacement) {
        return new NovaString(value.replace(target, replacement));
    }

    public NovaList split(String regex) {
        String[] parts = value.split(regex);
        NovaValue[] values = new NovaValue[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = new NovaString(parts[i]);
        }
        return new NovaList(values);
    }

    public NovaString repeat(int count) {
        if (count < 0) {
            throw new NovaException("Repeat count cannot be negative");
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(value);
        }
        return new NovaString(sb.toString());
    }

    public NovaString reverse() {
        return new NovaString(new StringBuilder(value).reverse().toString());
    }

    // ============ 比较 ============

    public int compareTo(NovaString other) {
        return this.value.compareTo(other.value);
    }

    public int compareToIgnoreCase(NovaString other) {
        return this.value.compareToIgnoreCase(other.value);
    }
}
