package nova.runtime.interpreter;
import nova.runtime.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 接口运行时类型
 *
 * <p>表示 Nova 接口。</p>
 *
 * <p>示例：</p>
 * <pre>
 * interface Loggable {
 *     fun log(msg: String)
 *
 *     // 默认方法
 *     fun warn(msg: String) = log("WARN $msg")
 * }
 * </pre>
 */
public final class NovaInterface extends NovaValue {

    private final String name;
    private final List<NovaInterface> superInterfaces;
    private final Map<String, NovaCallable> defaultMethods;  // 有实现的方法
    private final Set<String> abstractMethods;               // 抽象方法签名
    private final Environment closure;

    public NovaInterface(String name, Environment closure) {
        this.name = name;
        this.closure = closure;
        this.superInterfaces = new ArrayList<NovaInterface>();
        this.defaultMethods = new HashMap<String, NovaCallable>();
        this.abstractMethods = new HashSet<String>();
    }

    /** HIR 使用的简化构造器 */
    public NovaInterface(String name) {
        this(name, null);
    }

    /**
     * 获取接口名称
     */
    public String getName() {
        return name;
    }

    /**
     * 获取闭包环境
     */
    public Environment getClosure() {
        return closure;
    }

    /**
     * 添加父接口
     */
    public void addSuperInterface(NovaInterface superInterface) {
        superInterfaces.add(superInterface);
    }

    /**
     * 获取父接口列表
     */
    public List<NovaInterface> getSuperInterfaces() {
        return superInterfaces;
    }

    /**
     * 添加默认方法（有实现的方法）
     */
    public void addDefaultMethod(String methodName, NovaCallable method) {
        defaultMethods.put(methodName, method);
    }

    /**
     * 获取默认方法
     */
    public NovaCallable getDefaultMethod(String methodName) {
        // 先检查本接口
        NovaCallable method = defaultMethods.get(methodName);
        if (method != null) {
            return method;
        }
        // 递归检查父接口
        for (NovaInterface superInterface : superInterfaces) {
            method = superInterface.getDefaultMethod(methodName);
            if (method != null) {
                return method;
            }
        }
        return null;
    }

    /**
     * 添加抽象方法签名
     */
    public void addAbstractMethod(String methodName) {
        abstractMethods.add(methodName);
    }

    /**
     * 获取所有抽象方法（包括继承的）
     */
    public Set<String> getAllAbstractMethods() {
        Set<String> allAbstract = new HashSet<String>(abstractMethods);
        for (NovaInterface superInterface : superInterfaces) {
            allAbstract.addAll(superInterface.getAllAbstractMethods());
        }
        // 移除已有默认实现的方法
        allAbstract.removeAll(defaultMethods.keySet());
        return allAbstract;
    }

    /**
     * 检查是否有指定的抽象方法
     */
    public boolean hasAbstractMethod(String methodName) {
        return getAllAbstractMethods().contains(methodName);
    }

    /**
     * 检查类是否实现了此接口的所有方法
     *
     * @param clazz 要检查的类
     * @return 未实现的方法列表
     */
    public List<String> getUnimplementedMethods(NovaClass clazz) {
        List<String> unimplemented = new ArrayList<String>();
        for (String methodName : getAllAbstractMethods()) {
            if (clazz.findMethod(methodName) == null && getDefaultMethod(methodName) == null) {
                unimplemented.add(methodName);
            }
        }
        return unimplemented;
    }

    /**
     * 检查类是否完整实现了此接口
     */
    public boolean isImplementedBy(NovaClass clazz) {
        return getUnimplementedMethods(clazz).isEmpty();
    }

    /**
     * 检查是否是指定接口的子接口
     */
    public boolean isSubInterfaceOf(NovaInterface other) {
        if (this == other) {
            return true;
        }
        for (NovaInterface superInterface : superInterfaces) {
            if (superInterface.isSubInterfaceOf(other)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String getTypeName() {
        return "Interface:" + name;
    }

    @Override
    public Object toJavaValue() {
        return this;
    }

    @Override
    public String toString() {
        return "interface " + name;
    }
}
