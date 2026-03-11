package nova.runtime.host;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import nova.runtime.Function1;
import nova.runtime.Function2;
import nova.runtime.Function3;
import nova.runtime.NovaValueConversions;

public final class HostBindingRegistry {
    private final List<HostSymbolDescriptor> globals;
    private final Map<String, HostNamespaceDescriptor> namespaces;

    private HostBindingRegistry(List<HostSymbolDescriptor> globals,
                                Map<String, HostNamespaceDescriptor> namespaces) {
        this.globals = Collections.unmodifiableList(new ArrayList<HostSymbolDescriptor>(globals));
        this.namespaces = Collections.unmodifiableMap(new LinkedHashMap<String, HostNamespaceDescriptor>(namespaces));
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<HostSymbolDescriptor> globals() {
        return globals;
    }

    public Map<String, HostNamespaceDescriptor> namespaces() {
        return namespaces;
    }

    public HostNamespaceDescriptor namespace(String name) {
        return namespaces.get(name);
    }

    public HostNamespaceDescriptor resolveNamespace(String name) {
        String effectiveName = (name == null || name.trim().isEmpty()) ? "default" : name.trim();
        if (!"default".equals(effectiveName) && !namespaces.containsKey(effectiveName)) {
            throw new IllegalArgumentException("Unknown namespace: " + effectiveName);
        }

        LinkedHashMap<String, HostSymbolDescriptor> merged = new LinkedHashMap<String, HostSymbolDescriptor>();
        mergeSymbols(globals, merged);

        Set<String> visiting = new LinkedHashSet<String>();
        Set<String> visited = new LinkedHashSet<String>();
        if (namespaces.containsKey("default")) {
            mergeNamespace("default", merged, visiting, visited);
        }
        if (!"default".equals(effectiveName)) {
            mergeNamespace(effectiveName, merged, visiting, visited);
        }

        HostNamespaceDescriptor original = namespaces.get(effectiveName);
        List<String> extendsNamespaces = original != null ? original.getExtendsNamespaces() : Collections.<String>emptyList();
        return new HostNamespaceDescriptor(effectiveName, extendsNamespaces, new ArrayList<HostSymbolDescriptor>(merged.values()));
    }

    private void mergeNamespace(String namespaceName,
                                LinkedHashMap<String, HostSymbolDescriptor> merged,
                                Set<String> visiting,
                                Set<String> visited) {
        if (visited.contains(namespaceName)) {
            return;
        }
        if (!visiting.add(namespaceName)) {
            throw new IllegalStateException("Namespace inheritance cycle detected: " + namespaceName);
        }

        HostNamespaceDescriptor descriptor = namespaces.get(namespaceName);
        if (descriptor == null) {
            throw new IllegalStateException("Unknown namespace in extends chain: " + namespaceName);
        }

        for (String parent : descriptor.getExtendsNamespaces()) {
            if ("default".equals(parent) && visited.contains("default")) {
                continue;
            }
            mergeNamespace(parent, merged, visiting, visited);
        }

        mergeSymbols(descriptor.getGlobals(), merged);
        visiting.remove(namespaceName);
        visited.add(namespaceName);
    }

    private void mergeSymbols(List<HostSymbolDescriptor> symbols,
                              LinkedHashMap<String, HostSymbolDescriptor> merged) {
        for (HostSymbolDescriptor symbol : symbols) {
            merged.put(symbol.getName(), symbol);
        }
    }

    public static final class Builder {
        private final List<HostSymbolDescriptor> globals = new ArrayList<HostSymbolDescriptor>();
        private final LinkedHashMap<String, NamespaceBuilder> namespaces = new LinkedHashMap<String, NamespaceBuilder>();

        public Builder globalVariable(String name, Consumer<VariableBuilder> spec) {
            VariableBuilder builder = new VariableBuilder(name, HostSymbolKind.VARIABLE);
            spec.accept(builder);
            globals.add(builder.build());
            return this;
        }

        public Builder globalFunction(String name, Consumer<FunctionBuilder> spec) {
            FunctionBuilder builder = new FunctionBuilder(name);
            spec.accept(builder);
            globals.add(builder.build());
            return this;
        }

        public Builder globalObject(String name, Consumer<ObjectBuilder> spec) {
            ObjectBuilder builder = new ObjectBuilder(name);
            spec.accept(builder);
            globals.add(builder.build());
            return this;
        }

        public Builder namespace(String name, Consumer<NamespaceBuilder> spec) {
            NamespaceBuilder builder = namespaces.computeIfAbsent(name, NamespaceBuilder::new);
            spec.accept(builder);
            return this;
        }

        public HostBindingRegistry build() {
            LinkedHashMap<String, HostNamespaceDescriptor> builtNamespaces = new LinkedHashMap<String, HostNamespaceDescriptor>();
            for (Map.Entry<String, NamespaceBuilder> entry : namespaces.entrySet()) {
                builtNamespaces.put(entry.getKey(), entry.getValue().build());
            }
            return new HostBindingRegistry(globals, builtNamespaces);
        }
    }

    public static final class NamespaceBuilder {
        private final String name;
        private final List<String> extendsNamespaces = new ArrayList<String>();
        private final List<HostSymbolDescriptor> globals = new ArrayList<HostSymbolDescriptor>();

        private NamespaceBuilder(String name) {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("Namespace name must not be empty");
            }
            this.name = name.trim();
        }

        public NamespaceBuilder extendsNamespace(String namespaceName) {
            if (namespaceName == null || namespaceName.trim().isEmpty()) {
                throw new IllegalArgumentException("extends namespace must not be empty");
            }
            this.extendsNamespaces.add(namespaceName.trim());
            return this;
        }

        public NamespaceBuilder variable(String name, Consumer<VariableBuilder> spec) {
            VariableBuilder builder = new VariableBuilder(name, HostSymbolKind.VARIABLE);
            spec.accept(builder);
            globals.add(builder.build());
            return this;
        }

        public NamespaceBuilder function(String name, Consumer<FunctionBuilder> spec) {
            FunctionBuilder builder = new FunctionBuilder(name);
            spec.accept(builder);
            globals.add(builder.build());
            return this;
        }

        public NamespaceBuilder object(String name, Consumer<ObjectBuilder> spec) {
            ObjectBuilder builder = new ObjectBuilder(name);
            spec.accept(builder);
            globals.add(builder.build());
            return this;
        }

        private HostNamespaceDescriptor build() {
            return new HostNamespaceDescriptor(name, extendsNamespaces, globals);
        }
    }

    public abstract static class BaseSymbolBuilder<T extends BaseSymbolBuilder<T>> {
        private final String name;
        private String documentation;
        private String deprecatedMessage;
        private final List<String> examples = new ArrayList<String>();

        protected BaseSymbolBuilder(String name) {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("Symbol name must not be empty");
            }
            this.name = name.trim();
        }

        public T doc(String documentation) {
            this.documentation = documentation;
            return self();
        }

        public T deprecated(String message) {
            this.deprecatedMessage = message;
            return self();
        }

        public T example(String example) {
            if (example != null && !example.isEmpty()) {
                this.examples.add(example);
            }
            return self();
        }

        protected String name() {
            return name;
        }

        protected String documentation() {
            return documentation;
        }

        protected String deprecatedMessage() {
            return deprecatedMessage;
        }

        protected List<String> examples() {
            return examples;
        }

        protected abstract T self();
    }

    public static final class VariableBuilder extends BaseSymbolBuilder<VariableBuilder> {
        private final HostSymbolKind kind;
        private HostTypeRef type = HostTypes.ANY;
        private boolean mutable;
        private Object value;
        private Supplier<?> supplier;

        private VariableBuilder(String name, HostSymbolKind kind) {
            super(name);
            this.kind = kind;
        }

        public VariableBuilder type(String type) {
            return type(HostTypeRef.of(type));
        }

        public VariableBuilder type(HostTypeRef type) {
            this.type = type;
            return this;
        }

        public VariableBuilder mutable(boolean mutable) {
            this.mutable = mutable;
            return this;
        }

        public VariableBuilder mutable() {
            return mutable(true);
        }

        public VariableBuilder readonly() {
            return mutable(false);
        }

        public VariableBuilder value(Object value) {
            this.value = value;
            this.supplier = null;
            return this;
        }

        public VariableBuilder supplier(Supplier<?> supplier) {
            this.supplier = supplier;
            this.value = null;
            return this;
        }

        private HostSymbolDescriptor build() {
            if (kind == HostSymbolKind.PROPERTY) {
                return new HostPropertyDescriptor(name(), type, mutable, documentation(), deprecatedMessage(), examples());
            }
            return new HostVariableDescriptor(name(), type, mutable, documentation(), deprecatedMessage(), examples(), value, supplier);
        }

        @Override
        protected VariableBuilder self() {
            return this;
        }
    }

    public static final class FunctionBuilder extends BaseSymbolBuilder<FunctionBuilder> {
        private final List<HostParameterDescriptor> parameters = new ArrayList<HostParameterDescriptor>();
        private HostTypeRef returnType = HostTypes.UNIT;
        private HostFunctionInvoker invoker;

        private FunctionBuilder(String name) {
            super(name);
        }

        public FunctionBuilder param(String name, String type) {
            return param(name, HostTypeRef.of(type));
        }

        public FunctionBuilder param(String name, HostTypeRef type) {
            parameters.add(new HostParameterDescriptor(name, type, false));
            return this;
        }

        public FunctionBuilder vararg(String name, String elementType) {
            parameters.add(new HostParameterDescriptor(name, HostTypeRef.of(elementType), true));
            return this;
        }

        public FunctionBuilder returns(String type) {
            return returns(HostTypeRef.of(type));
        }

        public FunctionBuilder returns(HostTypeRef type) {
            this.returnType = type;
            return this;
        }

        public FunctionBuilder invoke(HostFunctionInvoker invoker) {
            this.invoker = invoker;
            return this;
        }

        public FunctionBuilder invoke0(Supplier<?> supplier) {
            return invoke(args -> supplier.get());
        }

        public FunctionBuilder invoke1(Function<Object, ?> fn) {
            return invoke(args -> fn.apply(args.length > 0 ? args[0] : null));
        }

        public FunctionBuilder invoke2(BiFunction<Object, Object, ?> fn) {
            return invoke(args -> fn.apply(args.length > 0 ? args[0] : null, args.length > 1 ? args[1] : null));
        }

        /**
         * 类型安全的无参数调用。
         *
         * @param returnType 返回类型（仅用于泛型推断）
         * @param fn         函数实现
         */
        public <R> FunctionBuilder invoke0(Class<R> returnType, Supplier<R> fn) {
            return invoke(args -> fn.get());
        }

        /**
         * 类型安全的单参数调用。参数自动从 Object 转换为 T1 类型。
         *
         * <pre>
         * .invoke1(String.class, name -> "Hello, " + name)
         * </pre>
         *
         * @param t1 第一个参数类型
         * @param fn 函数实现
         */
        public <T1, R> FunctionBuilder invoke1(Class<T1> t1, Function1<T1, R> fn) {
            return invoke(args -> {
                T1 a0 = NovaValueConversions.convertArg(args.length > 0 ? args[0] : null, t1);
                return fn.invoke(a0);
            });
        }

        /**
         * 类型安全的双参数调用。参数自动从 Object 转换为对应类型。
         *
         * <pre>
         * .invoke2(Integer.class, Integer.class, (a, b) -> a + b)
         * </pre>
         *
         * @param t1 第一个参数类型
         * @param t2 第二个参数类型
         * @param fn 函数实现
         */
        public <T1, T2, R> FunctionBuilder invoke2(Class<T1> t1, Class<T2> t2, Function2<T1, T2, R> fn) {
            return invoke(args -> {
                T1 a0 = NovaValueConversions.convertArg(args.length > 0 ? args[0] : null, t1);
                T2 a1 = NovaValueConversions.convertArg(args.length > 1 ? args[1] : null, t2);
                return fn.invoke(a0, a1);
            });
        }

        /**
         * 类型安全的三参数调用。参数自动从 Object 转换为对应类型。
         *
         * @param t1 第一个参数类型
         * @param t2 第二个参数类型
         * @param t3 第三个参数类型
         * @param fn 函数实现
         */
        public <T1, T2, T3, R> FunctionBuilder invoke3(Class<T1> t1, Class<T2> t2, Class<T3> t3,
                                                         Function3<T1, T2, T3, R> fn) {
            return invoke(args -> {
                T1 a0 = NovaValueConversions.convertArg(args.length > 0 ? args[0] : null, t1);
                T2 a1 = NovaValueConversions.convertArg(args.length > 1 ? args[1] : null, t2);
                T3 a2 = NovaValueConversions.convertArg(args.length > 2 ? args[2] : null, t3);
                return fn.invoke(a0, a1, a2);
            });
        }

        private HostFunctionDescriptor build() {
            return new HostFunctionDescriptor(name(), parameters, returnType, documentation(), deprecatedMessage(), examples(), invoker);
        }

        @Override
        protected FunctionBuilder self() {
            return this;
        }
    }

    public static final class ObjectBuilder extends BaseSymbolBuilder<ObjectBuilder> {
        private HostTypeRef type = HostTypes.ANY;
        private Object value;
        private Supplier<?> supplier;
        private final List<HostSymbolDescriptor> members = new ArrayList<HostSymbolDescriptor>();

        private ObjectBuilder(String name) {
            super(name);
        }

        public ObjectBuilder type(String type) {
            return type(HostTypeRef.of(type));
        }

        public ObjectBuilder type(HostTypeRef type) {
            this.type = type;
            return this;
        }

        public ObjectBuilder value(Object value) {
            this.value = value;
            this.supplier = null;
            return this;
        }

        public ObjectBuilder supplier(Supplier<?> supplier) {
            this.supplier = supplier;
            this.value = null;
            return this;
        }

        public ObjectBuilder property(String name, Consumer<VariableBuilder> spec) {
            VariableBuilder builder = new VariableBuilder(name, HostSymbolKind.PROPERTY);
            spec.accept(builder);
            members.add(builder.build());
            return this;
        }

        public ObjectBuilder function(String name, Consumer<FunctionBuilder> spec) {
            FunctionBuilder builder = new FunctionBuilder(name);
            spec.accept(builder);
            members.add(builder.build());
            return this;
        }

        public ObjectBuilder object(String name, Consumer<ObjectBuilder> spec) {
            ObjectBuilder builder = new ObjectBuilder(name);
            spec.accept(builder);
            members.add(builder.build());
            return this;
        }

        private HostObjectDescriptor build() {
            return new HostObjectDescriptor(name(), type, documentation(), deprecatedMessage(), examples(), value, supplier, members);
        }

        @Override
        protected ObjectBuilder self() {
            return this;
        }
    }
}
