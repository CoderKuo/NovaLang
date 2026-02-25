package nova.runtime;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Nova Map 值（有序映射）
 */
public final class NovaMap extends NovaValue implements Iterable<NovaValue> {

    private final Map<NovaValue, NovaValue> entries;

    public NovaMap() {
        this.entries = new LinkedHashMap<NovaValue, NovaValue>();
    }

    public NovaMap(Map<NovaValue, NovaValue> entries) {
        this.entries = new LinkedHashMap<NovaValue, NovaValue>(entries);
    }

    public Map<NovaValue, NovaValue> getEntries() {
        return entries;
    }

    @Override
    public String getTypeName() {
        return "Map";
    }

    @Override
    public String getNovaTypeName() {
        return "Map";
    }

    @Override
    public Object toJavaValue() {
        Map<Object, Object> result = new LinkedHashMap<Object, Object>();
        for (Map.Entry<NovaValue, NovaValue> entry : entries.entrySet()) {
            result.put(entry.getKey().toJavaValue(), entry.getValue().toJavaValue());
        }
        return result;
    }

    @Override
    public boolean isTruthy() {
        return !entries.isEmpty();
    }

    @Override
    public boolean isMap() {
        return true;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<NovaValue, NovaValue> entry : entries.entrySet()) {
            if (!first) sb.append(", ");
            first = false;

            NovaValue key = entry.getKey();
            NovaValue value = entry.getValue();

            if (key.isString()) {
                sb.append("\"").append(key.asString()).append("\"");
            } else {
                sb.append(key.toString());
            }
            sb.append(": ");
            if (value.isString()) {
                sb.append("\"").append(value.asString()).append("\"");
            } else {
                sb.append(value.toString());
            }
        }
        sb.append("}");
        return sb.toString();
    }

    @Override
    public boolean equals(NovaValue other) {
        if (other == null) return false;
        if (!(other instanceof NovaMap)) return false;
        NovaMap otherMap = (NovaMap) other;
        if (this.entries.size() != otherMap.entries.size()) return false;
        for (Map.Entry<NovaValue, NovaValue> entry : entries.entrySet()) {
            NovaValue otherValue = otherMap.get(entry.getKey());
            if (otherValue == null || !entry.getValue().equals(otherValue)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int h = 0;
        for (Map.Entry<NovaValue, NovaValue> entry : entries.entrySet()) {
            h += entry.getKey().hashCode() ^ entry.getValue().hashCode();
        }
        return h;
    }

    @Override
    public Iterator<NovaValue> iterator() {
        Iterator<Map.Entry<NovaValue, NovaValue>> entryIterator = entries.entrySet().iterator();
        return new Iterator<NovaValue>() {
            @Override
            public boolean hasNext() {
                return entryIterator.hasNext();
            }

            @Override
            public NovaValue next() {
                Map.Entry<NovaValue, NovaValue> entry = entryIterator.next();
                return NovaPair.of(entry.getKey(), entry.getValue());
            }
        };
    }

    // ============ Map 操作 ============

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public NovaValue get(NovaValue key) {
        NovaValue value = entries.get(key);
        return value != null ? value : NovaNull.NULL;
    }

    public NovaValue getOrDefault(NovaValue key, NovaValue defaultValue) {
        NovaValue value = entries.get(key);
        return value != null ? value : defaultValue;
    }

    public void put(NovaValue key, NovaValue value) {
        entries.put(key, value);
    }

    public NovaValue remove(NovaValue key) {
        NovaValue removed = entries.remove(key);
        return removed != null ? removed : NovaNull.NULL;
    }

    public void clear() {
        entries.clear();
    }

    public boolean containsKey(NovaValue key) {
        return entries.containsKey(key);
    }

    public boolean containsValue(NovaValue value) {
        for (NovaValue v : entries.values()) {
            if (v.equals(value)) {
                return true;
            }
        }
        return false;
    }

    public Set<NovaValue> keys() {
        return entries.keySet();
    }

    public NovaList keysList() {
        return new NovaList(new java.util.ArrayList<NovaValue>(entries.keySet()));
    }

    public NovaList valuesList() {
        return new NovaList(new java.util.ArrayList<NovaValue>(entries.values()));
    }

    public void putAll(NovaMap other) {
        entries.putAll(other.entries);
    }
}
