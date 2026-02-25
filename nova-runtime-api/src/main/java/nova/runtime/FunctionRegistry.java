package nova.runtime;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 函数注册表
 *
 * <p>管理从 Java 注册的全局函数。</p>
 */
public final class FunctionRegistry {

    private final Map<String, RegisteredFunction> functions = new ConcurrentHashMap<String, RegisteredFunction>();

    /**
     * 注册函数（通用版本）
     *
     * @param name           函数名
     * @param paramTypes     参数类型数组
     * @param returnType     返回类型
     * @param function       函数实现
     */
    public void register(String name, Class<?>[] paramTypes, Class<?> returnType, NativeFunction<?> function) {
        functions.put(name, new RegisteredFunction(name, paramTypes, returnType, function));
    }

    /**
     * 注册无参数函数
     */
    public <R> void register(String name, Class<R> returnType, Function0<R> function) {
        register(name, new Class<?>[0], returnType, new NativeFunction<R>() {
            @Override
            public R invoke(Object[] args) {
                return function.invoke();
            }
        });
    }

    /**
     * 注册单参数函数
     */
    @SuppressWarnings("unchecked")
    public <T1, R> void register(String name, Class<T1> t1, Class<R> returnType, Function1<T1, R> function) {
        register(name, new Class<?>[] { t1 }, returnType, new NativeFunction<R>() {
            @Override
            public R invoke(Object[] args) {
                return function.invoke((T1) args[0]);
            }
        });
    }

    /**
     * 注册双参数函数
     */
    @SuppressWarnings("unchecked")
    public <T1, T2, R> void register(String name, Class<T1> t1, Class<T2> t2, Class<R> returnType,
                                      Function2<T1, T2, R> function) {
        register(name, new Class<?>[] { t1, t2 }, returnType, new NativeFunction<R>() {
            @Override
            public R invoke(Object[] args) {
                return function.invoke((T1) args[0], (T2) args[1]);
            }
        });
    }

    /**
     * 注册三参数函数
     */
    @SuppressWarnings("unchecked")
    public <T1, T2, T3, R> void register(String name, Class<T1> t1, Class<T2> t2, Class<T3> t3,
                                          Class<R> returnType, Function3<T1, T2, T3, R> function) {
        register(name, new Class<?>[] { t1, t2, t3 }, returnType, new NativeFunction<R>() {
            @Override
            public R invoke(Object[] args) {
                return function.invoke((T1) args[0], (T2) args[1], (T3) args[2]);
            }
        });
    }

    /**
     * 查找函数
     *
     * @param name 函数名
     * @return 注册的函数，如果不存在返回 null
     */
    public RegisteredFunction lookup(String name) {
        return functions.get(name);
    }

    /**
     * 检查函数是否存在
     */
    public boolean contains(String name) {
        return functions.containsKey(name);
    }

    /**
     * 移除函数
     */
    public void unregister(String name) {
        functions.remove(name);
    }

    /**
     * 清空所有注册
     */
    public void clear() {
        functions.clear();
    }

    /**
     * 注册的函数信息
     */
    public static final class RegisteredFunction {
        private final String name;
        private final Class<?>[] paramTypes;
        private final Class<?> returnType;
        private final NativeFunction<?> function;

        public RegisteredFunction(String name, Class<?>[] paramTypes, Class<?> returnType,
                                  NativeFunction<?> function) {
            this.name = name;
            this.paramTypes = paramTypes;
            this.returnType = returnType;
            this.function = function;
        }

        public String getName() {
            return name;
        }

        public Class<?>[] getParamTypes() {
            return paramTypes;
        }

        public Class<?> getReturnType() {
            return returnType;
        }

        public NativeFunction<?> getFunction() {
            return function;
        }

        /**
         * 调用函数
         */
        public Object invoke(Object[] args) throws Exception {
            return function.invoke(args);
        }
    }
}
