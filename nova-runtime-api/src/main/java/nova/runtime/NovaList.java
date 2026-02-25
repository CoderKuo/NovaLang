package nova.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Nova List 值（可变列表）
 */
public final class NovaList extends NovaValue implements Iterable<NovaValue> {

    private final List<NovaValue> elements;

    public NovaList() {
        this.elements = new ArrayList<NovaValue>();
    }

    public NovaList(NovaValue[] values) {
        this.elements = new ArrayList<NovaValue>(Arrays.asList(values));
    }

    public NovaList(List<NovaValue> values) {
        this.elements = new ArrayList<NovaValue>(values);
    }

    public List<NovaValue> getElements() {
        return elements;
    }

    @Override
    public String getTypeName() {
        return "List";
    }

    @Override
    public String getNovaTypeName() {
        return "List";
    }

    @Override
    public Object toJavaValue() {
        List<Object> result = new ArrayList<Object>();
        for (NovaValue v : elements) {
            result.add(v.toJavaValue());
        }
        return result;
    }

    @Override
    public boolean isTruthy() {
        return !elements.isEmpty();
    }

    @Override
    public boolean isList() {
        return true;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < elements.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(elements.get(i).toString());
        }
        sb.append("]");
        return sb.toString();
    }

    @Override
    public boolean equals(NovaValue other) {
        if (other == null) return false;
        if (!(other instanceof NovaList)) return false;
        NovaList otherList = (NovaList) other;
        if (this.elements.size() != otherList.elements.size()) return false;
        for (int i = 0; i < elements.size(); i++) {
            if (!this.elements.get(i).equals(otherList.elements.get(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int h = 1;
        for (NovaValue e : elements) {
            h = 31 * h + e.hashCode();
        }
        return h;
    }

    @Override
    public Iterator<NovaValue> iterator() {
        return elements.iterator();
    }

    // ============ 列表操作 ============

    public int size() {
        return elements.size();
    }

    public boolean isEmpty() {
        return elements.isEmpty();
    }

    public NovaValue get(int index) {
        if (index < 0) {
            index = elements.size() + index;  // 支持负索引
        }
        if (index < 0 || index >= elements.size()) {
            throw new NovaException("List index out of bounds: " + index);
        }
        return elements.get(index);
    }

    public void set(int index, NovaValue value) {
        if (index < 0) {
            index = elements.size() + index;
        }
        if (index < 0 || index >= elements.size()) {
            throw new NovaException("List index out of bounds: " + index);
        }
        elements.set(index, value);
    }

    public void add(NovaValue value) {
        elements.add(value);
    }

    public void add(int index, NovaValue value) {
        elements.add(index, value);
    }

    public void addAt(int index, NovaValue value) {
        elements.add(index, value);
    }

    public NovaValue removeAt(int index) {
        if (index < 0) {
            index = elements.size() + index;
        }
        if (index < 0 || index >= elements.size()) {
            throw new NovaException("List index out of bounds: " + index);
        }
        return elements.remove(index);
    }

    public boolean remove(NovaValue value) {
        return elements.remove(value);
    }

    public void clear() {
        elements.clear();
    }

    public boolean contains(NovaValue value) {
        for (NovaValue elem : elements) {
            if (elem.equals(value)) {
                return true;
            }
        }
        return false;
    }

    public int indexOf(NovaValue value) {
        for (int i = 0; i < elements.size(); i++) {
            if (elements.get(i).equals(value)) {
                return i;
            }
        }
        return -1;
    }

    public NovaValue first() {
        if (elements.isEmpty()) {
            throw new RuntimeException("List is empty");
        }
        return elements.get(0);
    }

    public NovaValue last() {
        if (elements.isEmpty()) {
            throw new RuntimeException("List is empty");
        }
        return elements.get(elements.size() - 1);
    }

    public NovaList slice(int start, int end) {
        if (start < 0) start = elements.size() + start;
        if (end < 0) end = elements.size() + end;
        start = Math.max(0, Math.min(start, elements.size()));
        end = Math.max(start, Math.min(end, elements.size()));
        return new NovaList(elements.subList(start, end));
    }

    public NovaList concat(NovaList other) {
        NovaList result = new NovaList(this.elements);
        result.elements.addAll(other.elements);
        return result;
    }

    public NovaList reversed() {
        List<NovaValue> reversed = new ArrayList<NovaValue>(elements.size());
        for (int i = elements.size() - 1; i >= 0; i--) {
            reversed.add(elements.get(i));
        }
        return new NovaList(reversed);
    }

    public NovaList sorted() {
        List<NovaValue> copy = new ArrayList<NovaValue>(elements);
        Collections.sort(copy, new Comparator<NovaValue>() {
            @Override
            public int compare(NovaValue a, NovaValue b) {
                if (a.isNumber() && b.isNumber()) {
                    return Double.compare(a.asDouble(), b.asDouble());
                }
                return a.asString().compareTo(b.asString());
            }
        });
        return new NovaList(copy);
    }

    public NovaList distinct() {
        LinkedHashSet<NovaValue> seen = new LinkedHashSet<NovaValue>();
        for (NovaValue elem : elements) {
            seen.add(elem);
        }
        return new NovaList(new ArrayList<NovaValue>(seen));
    }

    public NovaString joinToString(String separator) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < elements.size(); i++) {
            if (i > 0) sb.append(separator);
            sb.append(elements.get(i).asString());
        }
        return NovaString.of(sb.toString());
    }
}
