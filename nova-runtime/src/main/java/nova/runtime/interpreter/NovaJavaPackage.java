package nova.runtime.interpreter;
import nova.runtime.*;

/**
 * Java 包路径引用，用于解析完全限定的 Java 类名（如 java.lang.StringBuilder）。
 * 在成员访问链中逐级拼接路径，直到匹配到一个实际的 Java 类。
 */
public final class NovaJavaPackage extends NovaValue {
    private final String path;

    public NovaJavaPackage(String path) {
        this.path = path;
    }

    public String getPath() {
        return path;
    }

    @Override
    public String getTypeName() {
        return "JavaPackage";
    }

    @Override
    public Object toJavaValue() {
        return path;
    }

    @Override
    public String toString() {
        return path;
    }
}
