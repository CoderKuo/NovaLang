package nova.runtime.interpreter;

import org.objectweb.asm.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import nova.runtime.interpreter.cache.BoundedCache;
import nova.runtime.interpreter.cache.CaffeineCache;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.objectweb.asm.Opcodes.*;

/**
 * 用 ASM 在运行时生成继承 Java 类/接口的子类。
 *
 * <p>生成的子类持有 {@link NovaBridgeCallback} 字段，
 * 每个被覆盖的方法在调用时委托给回调，从而桥接到 Nova 方法。</p>
 */
public final class JavaSubclassFactory {

    private static final String CALLBACK_FIELD = "$$callback";
    private static final String CALLBACK_DESC = Type.getDescriptor(NovaBridgeCallback.class);
    private static final AtomicInteger counter = new AtomicInteger(0);

    /** 缓存：key = 生成参数组合 → 生成的 Class */
    private static final BoundedCache<String, Class<?>> cache = new CaffeineCache<>(1024);

    /** 自定义 ClassLoader，用于加载生成的字节码 */
    private static final GeneratedClassLoader classLoader = new GeneratedClassLoader();

    private JavaSubclassFactory() {}

    /**
     * 生成一个继承 javaSuperclass 并实现 javaInterfaces 的子类。
     *
     * @param javaSuperclass   Java 父类，null 表示 Object
     * @param javaInterfaces   要实现的 Java 接口列表
     * @param novaMethodNames  Nova 代码中定义的方法名（需要覆盖的）
     * @param superCtorArgTypes 父类构造器参数类型
     * @return 生成的子类 Class
     */
    public static Class<?> generateSubclass(
            Class<?> javaSuperclass,
            List<Class<?>> javaInterfaces,
            Set<String> novaMethodNames,
            Class<?>[] superCtorArgTypes) {

        if (javaSuperclass == null) javaSuperclass = Object.class;

        // 检查 final 类
        if (Modifier.isFinal(javaSuperclass.getModifiers())) {
            throw new NovaRuntimeException("Cannot extend final class: " + javaSuperclass.getName());
        }

        // 构造缓存 key
        String cacheKey = buildCacheKey(javaSuperclass, javaInterfaces, novaMethodNames, superCtorArgTypes);
        Class<?> cached = cache.get(cacheKey);
        if (cached != null) return cached;

        String superInternalName = Type.getInternalName(javaSuperclass);
        String[] ifaceInternalNames = new String[javaInterfaces.size()];
        for (int i = 0; i < javaInterfaces.size(); i++) {
            ifaceInternalNames[i] = Type.getInternalName(javaInterfaces.get(i));
        }

        String generatedName = "nova/runtime/generated/Sub$" +
                javaSuperclass.getSimpleName() + "$" + counter.incrementAndGet();

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(V1_8, ACC_PUBLIC | ACC_SUPER, generatedName, null,
                superInternalName, ifaceInternalNames.length > 0 ? ifaceInternalNames : null);

        // $$callback 字段
        cw.visitField(ACC_PUBLIC, CALLBACK_FIELD, CALLBACK_DESC, null, null).visitEnd();

        // 构造器：匹配父类构造器签名
        generateConstructor(cw, generatedName, superInternalName, superCtorArgTypes);

        // 收集需要覆盖的方法
        Set<String> generated = new HashSet<>();

        // 从父类收集可覆盖方法
        collectAndOverrideMethods(cw, generatedName, superInternalName, javaSuperclass,
                novaMethodNames, generated, false);

        // 从接口收集抽象方法
        for (int i = 0; i < javaInterfaces.size(); i++) {
            collectAndOverrideMethods(cw, generatedName, superInternalName, javaInterfaces.get(i),
                    novaMethodNames, generated, true);
        }

        cw.visitEnd();
        byte[] bytecode = cw.toByteArray();

        Class<?> clazz = classLoader.defineClass(generatedName.replace('/', '.'), bytecode);
        cache.put(cacheKey, clazz);
        return clazz;
    }

    private static void generateConstructor(ClassWriter cw, String generatedName,
                                             String superInternalName, Class<?>[] superCtorArgTypes) {
        StringBuilder desc = new StringBuilder("(");
        for (Class<?> argType : superCtorArgTypes) {
            desc.append(Type.getDescriptor(argType));
        }
        desc.append(")V");

        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", desc.toString(), null, null);
        mv.visitCode();

        // this
        mv.visitVarInsn(ALOAD, 0);

        // 加载参数并调用 super
        int slot = 1;
        for (Class<?> argType : superCtorArgTypes) {
            mv.visitVarInsn(getLoadOpcode(argType), slot);
            slot += getSlotSize(argType);
        }
        mv.visitMethodInsn(INVOKESPECIAL, superInternalName, "<init>", desc.toString(), false);

        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void collectAndOverrideMethods(ClassWriter cw, String generatedName,
                                                   String superInternalName, Class<?> sourceClass,
                                                   Set<String> novaMethodNames,
                                                   Set<String> generated, boolean isInterface) {
        for (Method m : sourceClass.getMethods()) {
            if (Modifier.isFinal(m.getModifiers()) || Modifier.isStatic(m.getModifiers())) continue;
            if (m.getDeclaringClass() == Object.class) continue;

            String methodName = m.getName();
            String methodDesc = Type.getMethodDescriptor(m);
            String methodKey = methodName + methodDesc;

            if (!novaMethodNames.contains(methodName)) continue;
            if (generated.contains(methodKey)) continue;
            generated.add(methodKey);

            generateOverrideMethod(cw, generatedName, superInternalName, m, isInterface);
        }
    }

    private static void generateOverrideMethod(ClassWriter cw, String generatedName,
                                                String superInternalName, Method m,
                                                boolean isInterfaceMethod) {
        String methodName = m.getName();
        String methodDesc = Type.getMethodDescriptor(m);

        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, methodName, methodDesc, null, null);
        mv.visitCode();

        // if ($$callback != null) → 委托给 callback
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, generatedName, CALLBACK_FIELD, CALLBACK_DESC);
        Label noCallback = new Label();
        mv.visitJumpInsn(IFNULL, noCallback);

        // callback.invoke(methodName, new Object[]{ args... })
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, generatedName, CALLBACK_FIELD, CALLBACK_DESC);

        // 方法名
        mv.visitLdcInsn(methodName);

        // 创建 args 数组
        Class<?>[] paramTypes = m.getParameterTypes();
        mv.visitLdcInsn(paramTypes.length);
        mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");

        int slot = 1;
        for (int i = 0; i < paramTypes.length; i++) {
            mv.visitInsn(DUP);
            mv.visitLdcInsn(i);
            // 加载参数并装箱
            loadAndBox(mv, paramTypes[i], slot);
            mv.visitInsn(AASTORE);
            slot += getSlotSize(paramTypes[i]);
        }

        // 调用 callback.invoke
        mv.visitMethodInsn(INVOKEINTERFACE, Type.getInternalName(NovaBridgeCallback.class),
                "invoke", "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;", true);

        // 处理返回值
        Class<?> returnType = m.getReturnType();
        if (returnType == void.class) {
            mv.visitInsn(POP);
            mv.visitInsn(RETURN);
        } else if (returnType.isPrimitive()) {
            unboxAndReturn(mv, returnType);
        } else {
            mv.visitTypeInsn(CHECKCAST, Type.getInternalName(returnType));
            mv.visitInsn(ARETURN);
        }

        // callback 为 null → 调用 super（如果是类方法）
        mv.visitLabel(noCallback);
        if (!isInterfaceMethod && !Modifier.isAbstract(m.getModifiers())) {
            // super.method(args)
            mv.visitVarInsn(ALOAD, 0);
            slot = 1;
            for (Class<?> paramType : paramTypes) {
                mv.visitVarInsn(getLoadOpcode(paramType), slot);
                slot += getSlotSize(paramType);
            }
            mv.visitMethodInsn(INVOKESPECIAL, superInternalName, methodName, methodDesc, false);
            if (returnType == void.class) {
                mv.visitInsn(RETURN);
            } else if (returnType.isPrimitive()) {
                mv.visitInsn(getReturnOpcode(returnType));
            } else {
                mv.visitInsn(ARETURN);
            }
        } else {
            // 接口抽象方法没有 super 可调 → 返回默认值
            if (returnType == void.class) {
                mv.visitInsn(RETURN);
            } else if (returnType.isPrimitive()) {
                pushDefault(mv, returnType);
                mv.visitInsn(getReturnOpcode(returnType));
            } else {
                mv.visitInsn(ACONST_NULL);
                mv.visitInsn(ARETURN);
            }
        }

        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void loadAndBox(MethodVisitor mv, Class<?> type, int slot) {
        if (type == int.class) {
            mv.visitVarInsn(ILOAD, slot);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
        } else if (type == long.class) {
            mv.visitVarInsn(LLOAD, slot);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
        } else if (type == double.class) {
            mv.visitVarInsn(DLOAD, slot);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
        } else if (type == float.class) {
            mv.visitVarInsn(FLOAD, slot);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false);
        } else if (type == boolean.class) {
            mv.visitVarInsn(ILOAD, slot);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
        } else if (type == byte.class) {
            mv.visitVarInsn(ILOAD, slot);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false);
        } else if (type == char.class) {
            mv.visitVarInsn(ILOAD, slot);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false);
        } else if (type == short.class) {
            mv.visitVarInsn(ILOAD, slot);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false);
        } else {
            mv.visitVarInsn(ALOAD, slot);
        }
    }

    private static void unboxAndReturn(MethodVisitor mv, Class<?> type) {
        if (type == int.class) {
            mv.visitTypeInsn(CHECKCAST, "java/lang/Number");
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false);
            mv.visitInsn(IRETURN);
        } else if (type == long.class) {
            mv.visitTypeInsn(CHECKCAST, "java/lang/Number");
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number", "longValue", "()J", false);
            mv.visitInsn(LRETURN);
        } else if (type == double.class) {
            mv.visitTypeInsn(CHECKCAST, "java/lang/Number");
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number", "doubleValue", "()D", false);
            mv.visitInsn(DRETURN);
        } else if (type == float.class) {
            mv.visitTypeInsn(CHECKCAST, "java/lang/Number");
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number", "floatValue", "()F", false);
            mv.visitInsn(FRETURN);
        } else if (type == boolean.class) {
            mv.visitTypeInsn(CHECKCAST, "java/lang/Boolean");
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false);
            mv.visitInsn(IRETURN);
        } else if (type == byte.class) {
            mv.visitTypeInsn(CHECKCAST, "java/lang/Number");
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number", "byteValue", "()B", false);
            mv.visitInsn(IRETURN);
        } else if (type == char.class) {
            mv.visitTypeInsn(CHECKCAST, "java/lang/Character");
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Character", "charValue", "()C", false);
            mv.visitInsn(IRETURN);
        } else if (type == short.class) {
            mv.visitTypeInsn(CHECKCAST, "java/lang/Number");
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number", "shortValue", "()S", false);
            mv.visitInsn(IRETURN);
        }
    }

    private static void pushDefault(MethodVisitor mv, Class<?> type) {
        if (type == long.class) {
            mv.visitInsn(LCONST_0);
        } else if (type == double.class) {
            mv.visitInsn(DCONST_0);
        } else if (type == float.class) {
            mv.visitInsn(FCONST_0);
        } else {
            // int, boolean, byte, char, short
            mv.visitInsn(ICONST_0);
        }
    }

    private static int getLoadOpcode(Class<?> type) {
        if (type == long.class) return LLOAD;
        if (type == double.class) return DLOAD;
        if (type == float.class) return FLOAD;
        if (type.isPrimitive()) return ILOAD;  // int, boolean, byte, char, short
        return ALOAD;
    }

    private static int getReturnOpcode(Class<?> type) {
        if (type == long.class) return LRETURN;
        if (type == double.class) return DRETURN;
        if (type == float.class) return FRETURN;
        if (type.isPrimitive()) return IRETURN;
        return ARETURN;
    }

    private static int getSlotSize(Class<?> type) {
        return (type == long.class || type == double.class) ? 2 : 1;
    }

    private static String buildCacheKey(Class<?> superClass, List<Class<?>> interfaces,
                                         Set<String> methodNames, Class<?>[] ctorArgTypes) {
        StringBuilder sb = new StringBuilder();
        sb.append(superClass.getName());
        for (Class<?> iface : interfaces) {
            sb.append('+').append(iface.getName());
        }
        sb.append('|');
        List<String> sorted = new ArrayList<>(methodNames);
        Collections.sort(sorted);
        for (String m : sorted) {
            sb.append(m).append(',');
        }
        sb.append('|');
        for (Class<?> t : ctorArgTypes) {
            sb.append(t.getName()).append(',');
        }
        return sb.toString();
    }

    /**
     * 自定义 ClassLoader 用于加载运行时生成的子类
     */
    private static class GeneratedClassLoader extends ClassLoader {
        GeneratedClassLoader() {
            super(JavaSubclassFactory.class.getClassLoader());
        }

        Class<?> defineClass(String name, byte[] bytecode) {
            return defineClass(name, bytecode, 0, bytecode.length);
        }
    }
}
