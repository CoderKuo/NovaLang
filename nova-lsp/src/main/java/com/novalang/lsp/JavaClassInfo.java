package com.novalang.lsp;

import java.util.ArrayList;
import java.util.List;

/**
 * Java 类的元信息，用于 LSP 补全。
 * 由 ASM 解析字节码后填充。
 */
public class JavaClassInfo {
    String className;
    String superClassName;
    List<String> interfaceNames = new ArrayList<>();
    List<String> typeParams = new ArrayList<>();     // 类级泛型参数: ["K", "V"]
    List<MethodInfo> methods = new ArrayList<>();
    List<FieldInfo> fields = new ArrayList<>();

    static class MethodInfo {
        String name;
        String returnType;             // 简名用于显示: "Boolean", "String", "ArrayList"
        String returnTypeFullName;     // 全限定名用于类型解析: "java.util.ArrayList"
        int genericReturnTypeIndex;    // 返回类型在类泛型参数中的索引, -1 表示非泛型
        List<String> paramTypes;
        boolean isStatic;

        MethodInfo(String name, String returnType, String returnTypeFullName,
                   int genericReturnTypeIndex, List<String> paramTypes, boolean isStatic) {
            this.name = name;
            this.returnType = returnType;
            this.returnTypeFullName = returnTypeFullName;
            this.genericReturnTypeIndex = genericReturnTypeIndex;
            this.paramTypes = paramTypes;
            this.isStatic = isStatic;
        }
    }

    static class FieldInfo {
        String name;
        String type;
        boolean isStatic;

        FieldInfo(String name, String type, boolean isStatic) {
            this.name = name;
            this.type = type;
            this.isStatic = isStatic;
        }
    }
}
