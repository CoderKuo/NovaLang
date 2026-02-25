package com.novalang.ir.mir;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * MIR 模块（编译单元）。
 */
public class MirModule {

    private final String packageName;
    private final List<MirClass> classes;
    private final List<MirFunction> topLevelFunctions;
    private List<ExtensionPropertyInfo> extensionProperties = Collections.emptyList();

    public MirModule(String packageName, List<MirClass> classes,
                     List<MirFunction> topLevelFunctions) {
        this.packageName = packageName;
        this.classes = classes;
        this.topLevelFunctions = topLevelFunctions;
    }

    public String getPackageName() { return packageName; }
    public List<MirClass> getClasses() { return classes; }
    public List<MirFunction> getTopLevelFunctions() { return topLevelFunctions; }

    public List<ExtensionPropertyInfo> getExtensionProperties() { return extensionProperties; }
    public void setExtensionProperties(List<ExtensionPropertyInfo> props) { this.extensionProperties = props; }

    private List<ExtensionFunctionInfo> extensionFunctions = Collections.emptyList();
    public List<ExtensionFunctionInfo> getExtensionFunctions() { return extensionFunctions; }
    public void setExtensionFunctions(List<ExtensionFunctionInfo> funcs) { this.extensionFunctions = funcs; }

    /** Java import 映射: 简单名 → 全限定类名（dot 格式，如 "ArrayList" → "java.util.ArrayList"） */
    private Map<String, String> javaImports = Collections.emptyMap();
    public Map<String, String> getJavaImports() { return javaImports; }
    public void setJavaImports(Map<String, String> imports) { this.javaImports = imports; }

    /** Java static import 映射: 简单名 → 全限定名（如 "MAX_VALUE" → "java.lang.Byte.MAX_VALUE"） */
    private Map<String, String> staticImports = Collections.emptyMap();
    public Map<String, String> getStaticImports() { return staticImports; }
    public void setStaticImports(Map<String, String> imports) { this.staticImports = imports; }

    /** Java wildcard import 包前缀列表（如 "java.util.*" → "java.util."） */
    private List<String> wildcardJavaImports = Collections.emptyList();
    public List<String> getWildcardJavaImports() { return wildcardJavaImports; }
    public void setWildcardJavaImports(List<String> imports) { this.wildcardJavaImports = imports; }

    /** Nova 模块 import 元数据 */
    private List<NovaImportInfo> novaImports = Collections.emptyList();
    public List<NovaImportInfo> getNovaImports() { return novaImports; }
    public void setNovaImports(List<NovaImportInfo> imports) { this.novaImports = imports; }

    public static class NovaImportInfo {
        public final String qualifiedName;
        public final String alias;     // null if no alias
        public final boolean wildcard;

        public NovaImportInfo(String qualifiedName, String alias, boolean wildcard) {
            this.qualifiedName = qualifiedName;
            this.alias = alias;
            this.wildcard = wildcard;
        }
    }

    /** 扩展函数元数据 */
    public static class ExtensionFunctionInfo {
        public final String receiverType;  // 接收者类型内部名（如 "java/lang/String"）
        public final String functionName;  // 函数名（如 "exclaim"）

        public ExtensionFunctionInfo(String receiverType, String functionName) {
            this.receiverType = receiverType;
            this.functionName = functionName;
        }
    }

    /** 扩展属性元数据 */
    public static class ExtensionPropertyInfo {
        public final String receiverType;  // 接收者类型内部名（如 "java/lang/String"）
        public final String propertyName;  // 属性名（如 "len"）
        public final String getterFuncName; // getter 函数名（如 "$extProp$String$len"）

        public ExtensionPropertyInfo(String receiverType, String propertyName, String getterFuncName) {
            this.receiverType = receiverType;
            this.propertyName = propertyName;
            this.getterFuncName = getterFuncName;
        }
    }
}
