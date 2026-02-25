package com.novalang.lsp;

import org.objectweb.asm.*;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.*;
import java.util.logging.Logger;

/**
 * 用 ASM 解析 Java 类字节码，提取 public 方法/字段信息。
 * 支持 JDK 类（通过 ClassLoader）和用户 JAR（通过 classpath 配置）。
 * 自动继承父类和接口的方法，解析泛型签名用于类型参数替换。
 *
 * <p>JAR 文件使用索引缓存：首次访问时扫描所有条目建立类名→JAR路径索引，
 * 后续查找直接定位，避免反复遍历和打开 JAR。</p>
 */
public class JavaClassResolver {
    private static final Logger LOG = Logger.getLogger(JavaClassResolver.class.getName());

    private final Map<String, JavaClassInfo> cache = new ConcurrentHashMap<>();
    private final List<String> classpathEntries;

    /** 类名(entryPath) → JAR 文件路径的索引，延迟构建 */
    private volatile Map<String, String> jarIndex;

    public JavaClassResolver(List<String> classpath) {
        this.classpathEntries = classpath != null ? classpath : Collections.emptyList();
    }

    /**
     * 解析指定 Java 类（含继承链），返回类信息。结果会被缓存。
     */
    public JavaClassInfo resolve(String className) {
        if (className == null || className.isEmpty()) return null;

        JavaClassInfo cached = cache.get(className);
        if (cached != null) return cached;

        byte[] bytecode = loadBytecode(className);
        if (bytecode == null) return null;

        JavaClassInfo info = parseClass(bytecode);
        if (info == null) return null;

        // 先缓存（防止继承链循环）
        cache.put(className, info);

        // 继承父类和接口的方法
        Set<String> defined = new HashSet<>();
        for (JavaClassInfo.MethodInfo m : info.methods) {
            defined.add(m.name + m.paramTypes);
        }

        if (info.superClassName != null) {
            JavaClassInfo superInfo = resolve(info.superClassName);
            if (superInfo != null) {
                inheritMethods(info, superInfo, defined);
            }
        }

        for (String ifaceName : info.interfaceNames) {
            JavaClassInfo ifaceInfo = resolve(ifaceName);
            if (ifaceInfo != null) {
                inheritMethods(info, ifaceInfo, defined);
            }
        }

        return info;
    }

    private void inheritMethods(JavaClassInfo target, JavaClassInfo source, Set<String> defined) {
        for (JavaClassInfo.MethodInfo m : source.methods) {
            String key = m.name + m.paramTypes;
            if (!defined.contains(key)) {
                target.methods.add(m);
                defined.add(key);
            }
        }
    }

    // ============ 字节码加载 ============

    private byte[] loadBytecode(String className) {
        byte[] data = loadFromSystem(className);
        if (data != null) return data;
        return loadFromClasspath(className);
    }

    private byte[] loadFromSystem(String className) {
        String resourcePath = className.replace('.', '/') + ".class";
        try (InputStream is = ClassLoader.getSystemResourceAsStream(resourcePath)) {
            if (is != null) {
                return readAllBytes(is);
            }
        } catch (IOException e) {
            LOG.fine("Failed to load system class: " + className);
        }
        return null;
    }

    private byte[] loadFromClasspath(String className) {
        String entryPath = className.replace('.', '/') + ".class";

        for (String cpEntry : classpathEntries) {
            File file = new File(cpEntry);
            if (!file.exists()) continue;

            if (file.isDirectory()) {
                File classFile = new File(file, entryPath);
                if (classFile.exists()) {
                    try (FileInputStream fis = new FileInputStream(classFile)) {
                        return readAllBytes(fis);
                    } catch (IOException e) {
                        LOG.fine("Failed to read class file: " + classFile);
                    }
                }
            }
        }

        // JAR 查找：使用索引缓存
        return loadFromJarIndex(entryPath);
    }

    /**
     * 使用 JAR 索引快速查找类。首次访问时构建索引。
     */
    private byte[] loadFromJarIndex(String entryPath) {
        if (jarIndex == null) {
            synchronized (this) {
                if (jarIndex == null) {
                    jarIndex = buildJarIndex();
                }
            }
        }

        String jarPath = jarIndex.get(entryPath);
        if (jarPath == null) return null;

        try (JarFile jar = new JarFile(jarPath)) {
            JarEntry entry = jar.getJarEntry(entryPath);
            if (entry != null) {
                try (InputStream is = jar.getInputStream(entry)) {
                    return readAllBytes(is);
                }
            }
        } catch (IOException e) {
            LOG.fine("Failed to read from JAR: " + jarPath);
        }
        return null;
    }

    /**
     * 扫描所有 JAR 文件，建立 entryPath → jarPath 索引
     */
    private Map<String, String> buildJarIndex() {
        Map<String, String> index = new HashMap<>();
        for (String cpEntry : classpathEntries) {
            if (!cpEntry.endsWith(".jar")) continue;
            File file = new File(cpEntry);
            if (!file.exists()) continue;

            try (JarFile jar = new JarFile(file)) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (name.endsWith(".class") && !entry.isDirectory()) {
                        index.putIfAbsent(name, cpEntry);
                    }
                }
            } catch (IOException e) {
                LOG.fine("Failed to index JAR: " + cpEntry);
            }
        }
        LOG.info("JAR index built: " + index.size() + " classes from " + classpathEntries.size() + " entries");
        return index;
    }

    // ============ 字节码解析 ============

    private JavaClassInfo parseClass(byte[] bytecode) {
        try {
            ClassReader reader = new ClassReader(bytecode);
            JavaClassInfo info = new JavaClassInfo();

            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public void visit(int version, int access, String name,
                                  String signature, String superName, String[] interfaces) {
                    info.className = name.replace('/', '.');
                    if (superName != null) {
                        info.superClassName = superName.replace('/', '.');
                    }
                    if (interfaces != null) {
                        for (String iface : interfaces) {
                            info.interfaceNames.add(iface.replace('/', '.'));
                        }
                    }
                    info.typeParams = parseClassTypeParams(signature);
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    if ((access & Opcodes.ACC_PUBLIC) != 0 && !name.equals("<clinit>")) {
                        info.methods.add(parseMethodInfo(access, name, descriptor, signature, info.typeParams));
                    }
                    return null;
                }

                @Override
                public FieldVisitor visitField(int access, String name, String descriptor,
                                               String signature, Object value) {
                    if ((access & Opcodes.ACC_PUBLIC) != 0) {
                        info.fields.add(parseFieldInfo(access, name, descriptor));
                    }
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

            return info;
        } catch (Exception e) {
            LOG.fine("Failed to parse class bytecode: " + e.getMessage());
            return null;
        }
    }

    private JavaClassInfo.MethodInfo parseMethodInfo(int access, String name, String descriptor,
                                                      String signature, List<String> classTypeParams) {
        boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
        Type returnType = Type.getReturnType(descriptor);
        Type[] argTypes = Type.getArgumentTypes(descriptor);

        List<String> paramTypes = new ArrayList<>();
        for (Type t : argTypes) {
            paramTypes.add(typeToSimpleName(t));
        }

        // 从泛型签名解析返回类型的类型参数索引
        String genericReturnName = parseGenericReturnType(signature);
        int genericReturnTypeIndex = -1;
        if (genericReturnName != null) {
            genericReturnTypeIndex = classTypeParams.indexOf(genericReturnName);
        }

        String displayName = name.equals("<init>") ? "<init>" : name;
        return new JavaClassInfo.MethodInfo(displayName, typeToSimpleName(returnType),
                typeToFullName(returnType), genericReturnTypeIndex, paramTypes, isStatic);
    }

    private JavaClassInfo.FieldInfo parseFieldInfo(int access, String name, String descriptor) {
        boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
        Type type = Type.getType(descriptor);
        return new JavaClassInfo.FieldInfo(name, typeToSimpleName(type), isStatic);
    }

    // ============ 泛型签名解析 ============

    /**
     * 从类签名中提取类型参数名列表。
     * 例如 HashMap 的签名 → ["K", "V"]
     */
    private List<String> parseClassTypeParams(String signature) {
        if (signature == null) return Collections.emptyList();
        List<String> params = new ArrayList<>();
        try {
            new SignatureReader(signature).accept(new SignatureVisitor(Opcodes.ASM9) {
                @Override
                public void visitFormalTypeParameter(String name) {
                    params.add(name);
                }
            });
        } catch (Exception e) {
            // 签名格式错误，忽略
        }
        return params;
    }

    /**
     * 从方法签名中解析返回类型的类型变量名。
     * 例如 getOrDefault 的签名 "(TK;TV;)TV;" → 返回 "V"
     * 如果返回类型不是类型变量，返回 null。
     */
    private String parseGenericReturnType(String signature) {
        if (signature == null) return null;
        String[] result = {null};
        try {
            new SignatureReader(signature).accept(new SignatureVisitor(Opcodes.ASM9) {
                @Override
                public SignatureVisitor visitReturnType() {
                    return new SignatureVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitTypeVariable(String name) {
                            result[0] = name;
                        }
                    };
                }
            });
        } catch (Exception e) {
            // 签名格式错误，忽略
        }
        return result[0];
    }

    // ============ 类型名转换 ============

    private String typeToFullName(Type type) {
        switch (type.getSort()) {
            case Type.VOID:    return "void";
            case Type.BOOLEAN: return "Boolean";
            case Type.CHAR:    return "Char";
            case Type.BYTE:    return "Byte";
            case Type.SHORT:   return "Short";
            case Type.INT:     return "Int";
            case Type.FLOAT:   return "Float";
            case Type.LONG:    return "Long";
            case Type.DOUBLE:  return "Double";
            case Type.ARRAY:
                return typeToFullName(type.getElementType()) + "[]";
            case Type.OBJECT:
                return type.getClassName();
            default:
                return type.getClassName();
        }
    }

    private String typeToSimpleName(Type type) {
        switch (type.getSort()) {
            case Type.VOID:    return "void";
            case Type.BOOLEAN: return "Boolean";
            case Type.CHAR:    return "Char";
            case Type.BYTE:    return "Byte";
            case Type.SHORT:   return "Short";
            case Type.INT:     return "Int";
            case Type.FLOAT:   return "Float";
            case Type.LONG:    return "Long";
            case Type.DOUBLE:  return "Double";
            case Type.ARRAY:
                return typeToSimpleName(type.getElementType()) + "[]";
            case Type.OBJECT:
                String fullName = type.getClassName();
                int lastDot = fullName.lastIndexOf('.');
                return lastDot >= 0 ? fullName.substring(lastDot + 1) : fullName;
            default:
                return type.getClassName();
        }
    }

    private byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int n;
        while ((n = is.read(tmp)) != -1) {
            buf.write(tmp, 0, n);
        }
        return buf.toByteArray();
    }
}
