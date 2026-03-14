package com.novalang.runtime.bench;

import com.novalang.runtime.NovaDynamic;
import com.novalang.runtime.stdlib.ListExtensions;
import com.novalang.runtime.stdlib.StringExtensions;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 8, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
@Threads(1)
public class NovaDynamicBenchmark {

    @State(Scope.Thread)
    public static class HotState {
        BenchBean[] beans;
        int[] values;
        String[] strings;
        List<List<Integer>> numberLists;
        int index;

        @Setup(Level.Trial)
        public void setUp() {
            beans = new BenchBean[]{
                    new BenchBean(41),
                    new BenchBean(42),
                    new BenchBean(43),
                    new BenchBean(44)
            };
            values = new int[]{1, 2, 3, 5, 8, 13, 21, 34};
            strings = new String[]{"alpha", "betagamma", "delta123", "omega"};
            numberLists = Arrays.asList(
                    Arrays.asList(1, 2, 3, 4),
                    Arrays.asList(5, 8, 13, 21),
                    Arrays.asList(34, 55, 89, 144),
                    Arrays.asList(233, 377, 610, 987)
            );
            index = 0;
            NovaDynamic.getMember(beans[0], "value");
            NovaDynamic.setMember(beans[0], "value", 1);
            NovaDynamic.invokeMethod(beans[0], "add", 1, 2);
            NovaDynamic.invoke2(beans[0], "add", 1, 2);
            NovaDynamic.invokeMethod(beans[0], "add4", 1, 2, 3, 4);
            NovaDynamic.invokeMethod(beans[0], "add5", 1, 2, 3, 4, 5);
            NovaDynamic.invokeMethod(beans[0], "add6", 1, 2, 3, 4, 5, 6);
            NovaDynamic.invokeMethod(beans[0], "sumAll", 1, 2, 3, 4);
            NovaDynamic.invokeMethod(beans[0], "sumAll", 1, 2, 3, 4, 5, 6, 7, 8);
            NovaDynamic.invokeMethod(StaticTarget.class, "twice", 1);
            NovaDynamic.invoke0(strings[0], "count");
            NovaDynamic.invoke1(strings[0], "take", 3);
            NovaDynamic.invoke0(numberLists.get(0), "sum");
        }

        BenchBean nextBean() {
            return beans[index++ & (beans.length - 1)];
        }

        int nextValue() {
            return values[index++ & (values.length - 1)];
        }

        String nextString() {
            return strings[index++ & (strings.length - 1)];
        }

        List<Integer> nextNumberList() {
            return numberLists.get(index++ & (numberLists.size() - 1));
        }
    }

    @State(Scope.Thread)
    public static class ColdState {
        BenchBean[] beans;
        int[] values;
        int index;
        Field getterCacheField;
        Field setterCacheField;
        Field methodCacheField;
        Field staticMethodCacheField;

        @Setup(Level.Trial)
        public void setUp() {
            beans = new BenchBean[]{
                    new BenchBean(41),
                    new BenchBean(42),
                    new BenchBean(43),
                    new BenchBean(44)
            };
            values = new int[]{1, 2, 3, 5, 8, 13, 21, 34};
            index = 0;
            getterCacheField = cacheField("getterCache");
            setterCacheField = cacheField("setterCache");
            methodCacheField = cacheField("methodCache");
            staticMethodCacheField = cacheField("staticMethodCache");
        }

        @Setup(Level.Invocation)
        public void clearCaches() {
            index = 0;
            for (BenchBean bean : beans) {
                bean.value = 42;
            }
            clearCache(getterCacheField);
            clearCache(setterCacheField);
            clearCache(methodCacheField);
            clearCache(staticMethodCacheField);
        }

        BenchBean nextBean() {
            return beans[index++ & (beans.length - 1)];
        }

        int nextValue() {
            return values[index++ & (values.length - 1)];
        }
    }

    public static class BenchBean {
        private int value;

        BenchBean(int value) {
            this.value = value;
        }

        @CompilerControl(CompilerControl.Mode.DONT_INLINE)
        public int getValue() {
            return value;
        }

        @CompilerControl(CompilerControl.Mode.DONT_INLINE)
        public void setValue(int value) {
            this.value = value;
        }

        @CompilerControl(CompilerControl.Mode.DONT_INLINE)
        public int add(int a, int b) {
            return value + a + b;
        }

        @CompilerControl(CompilerControl.Mode.DONT_INLINE)
        public int add4(int a, int b, int c, int d) {
            return value + a + b + c + d;
        }

        @CompilerControl(CompilerControl.Mode.DONT_INLINE)
        public int add5(int a, int b, int c, int d, int e) {
            return value + a + b + c + d + e;
        }

        @CompilerControl(CompilerControl.Mode.DONT_INLINE)
        public int add6(int a, int b, int c, int d, int e, int f) {
            return value + a + b + c + d + e + f;
        }

        @CompilerControl(CompilerControl.Mode.DONT_INLINE)
        public int sumAll(int... items) {
            int total = value;
            for (int item : items) {
                total += item;
            }
            return total;
        }
    }

    public static class StaticTarget {
        @CompilerControl(CompilerControl.Mode.DONT_INLINE)
        public static int twice(int value) {
            return value * 2 + 1;
        }
    }

    @Benchmark
    public int directGetter(HotState state) {
        return state.nextBean().getValue();
    }

    @Benchmark
    public Object dynamicGetMember(HotState state) {
        return NovaDynamic.getMember(state.nextBean(), "value");
    }

    @Benchmark
    public int directSetter(HotState state) {
        BenchBean bean = state.nextBean();
        int value = state.nextValue();
        bean.setValue(value);
        return bean.getValue();
    }

    @Benchmark
    public int dynamicSetMember(HotState state) {
        BenchBean bean = state.nextBean();
        int value = state.nextValue();
        NovaDynamic.setMember(bean, "value", value);
        return bean.getValue();
    }

    @Benchmark
    public int directMethod(HotState state) {
        BenchBean bean = state.nextBean();
        int left = state.nextValue();
        return bean.add(left, left + 1);
    }

    @Benchmark
    public Object dynamicInvokeMethod(HotState state) {
        BenchBean bean = state.nextBean();
        int left = state.nextValue();
        return NovaDynamic.invokeMethod(bean, "add", left, left + 1);
    }

    @Benchmark
    public Object dynamicInvoke2(HotState state) {
        BenchBean bean = state.nextBean();
        int left = state.nextValue();
        return NovaDynamic.invoke2(bean, "add", left, left + 1);
    }

    @Benchmark
    public int directMethod4(HotState state) {
        BenchBean bean = state.nextBean();
        int left = state.nextValue();
        return bean.add4(left, left + 1, left + 2, left + 3);
    }

    @Benchmark
    public Object dynamicInvokeMethod4(HotState state) {
        BenchBean bean = state.nextBean();
        int left = state.nextValue();
        return NovaDynamic.invokeMethod(bean, "add4", left, left + 1, left + 2, left + 3);
    }

    @Benchmark
    public int directMethod5(HotState state) {
        BenchBean bean = state.nextBean();
        int left = state.nextValue();
        return bean.add5(left, left + 1, left + 2, left + 3, left + 4);
    }

    @Benchmark
    public Object dynamicInvokeMethod5(HotState state) {
        BenchBean bean = state.nextBean();
        int left = state.nextValue();
        return NovaDynamic.invokeMethod(bean, "add5", left, left + 1, left + 2, left + 3, left + 4);
    }

    @Benchmark
    public int directMethod6(HotState state) {
        BenchBean bean = state.nextBean();
        int left = state.nextValue();
        return bean.add6(left, left + 1, left + 2, left + 3, left + 4, left + 5);
    }

    @Benchmark
    public Object dynamicInvokeMethod6(HotState state) {
        BenchBean bean = state.nextBean();
        int left = state.nextValue();
        return NovaDynamic.invokeMethod(bean, "add6", left, left + 1, left + 2, left + 3, left + 4, left + 5);
    }

    @Benchmark
    public int directVarargsMethod(HotState state) {
        BenchBean bean = state.nextBean();
        int left = state.nextValue();
        return bean.sumAll(left, left + 1, left + 2, left + 3);
    }

    @Benchmark
    public Object dynamicInvokeVarargsMethod(HotState state) {
        BenchBean bean = state.nextBean();
        int left = state.nextValue();
        return NovaDynamic.invokeMethod(bean, "sumAll", left, left + 1, left + 2, left + 3);
    }

    @Benchmark
    public int directVarargsMethod8(HotState state) {
        BenchBean bean = state.nextBean();
        int left = state.nextValue();
        return bean.sumAll(left, left + 1, left + 2, left + 3, left + 4, left + 5, left + 6, left + 7);
    }

    @Benchmark
    public Object dynamicInvokeVarargsMethod8(HotState state) {
        BenchBean bean = state.nextBean();
        int left = state.nextValue();
        return NovaDynamic.invokeMethod(bean, "sumAll", left, left + 1, left + 2, left + 3, left + 4, left + 5, left + 6, left + 7);
    }

    @Benchmark
    public int directStaticMethod(HotState state) {
        return StaticTarget.twice(state.nextValue());
    }

    @Benchmark
    public Object dynamicStaticMethod(HotState state) {
        return NovaDynamic.invokeMethod(StaticTarget.class, "twice", state.nextValue());
    }

    @Benchmark
    public Object directStringExtensionCount(HotState state) {
        return StringExtensions.count(state.nextString());
    }

    @Benchmark
    public Object dynamicStringExtensionCount(HotState state) {
        return NovaDynamic.invoke0(state.nextString(), "count");
    }

    @Benchmark
    public Object directStringExtensionTake(HotState state) {
        return StringExtensions.take(state.nextString(), 3);
    }

    @Benchmark
    public Object dynamicStringExtensionTake(HotState state) {
        return NovaDynamic.invoke1(state.nextString(), "take", 3);
    }

    @Benchmark
    public Object directListExtensionSum(HotState state) {
        return ListExtensions.sum(state.nextNumberList());
    }

    @Benchmark
    public Object dynamicListExtensionSum(HotState state) {
        return NovaDynamic.invoke0(state.nextNumberList(), "sum");
    }

    @Benchmark
    public Object coldGetMember(ColdState state) {
        return NovaDynamic.getMember(state.nextBean(), "value");
    }

    @Benchmark
    public int coldSetMember(ColdState state) {
        BenchBean bean = state.nextBean();
        int value = state.nextValue();
        NovaDynamic.setMember(bean, "value", value);
        return bean.getValue();
    }

    @Benchmark
    public Object coldInvokeMethod(ColdState state) {
        BenchBean bean = state.nextBean();
        int left = state.nextValue();
        return NovaDynamic.invokeMethod(bean, "add", left, left + 1);
    }

    @Benchmark
    public Object coldInvokeMethod4(ColdState state) {
        BenchBean bean = state.nextBean();
        int left = state.nextValue();
        return NovaDynamic.invokeMethod(bean, "add4", left, left + 1, left + 2, left + 3);
    }

    @Benchmark
    public Object coldInvokeMethod5(ColdState state) {
        BenchBean bean = state.nextBean();
        int left = state.nextValue();
        return NovaDynamic.invokeMethod(bean, "add5", left, left + 1, left + 2, left + 3, left + 4);
    }

    @Benchmark
    public Object coldInvokeMethod6(ColdState state) {
        BenchBean bean = state.nextBean();
        int left = state.nextValue();
        return NovaDynamic.invokeMethod(bean, "add6", left, left + 1, left + 2, left + 3, left + 4, left + 5);
    }

    @Benchmark
    public Object coldInvokeVarargsMethod(ColdState state) {
        BenchBean bean = state.nextBean();
        int left = state.nextValue();
        return NovaDynamic.invokeMethod(bean, "sumAll", left, left + 1, left + 2, left + 3);
    }

    @Benchmark
    public Object coldInvokeVarargsMethod8(ColdState state) {
        BenchBean bean = state.nextBean();
        int left = state.nextValue();
        return NovaDynamic.invokeMethod(bean, "sumAll", left, left + 1, left + 2, left + 3, left + 4, left + 5, left + 6, left + 7);
    }

    private static Field cacheField(String name) {
        try {
            Field field = NovaDynamic.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to access cache field " + name, e);
        }
    }

    private static void clearCache(Field field) {
        try {
            ((ConcurrentHashMap<?, ?>) field.get(null)).clear();
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Failed to clear cache " + field.getName(), e);
        }
    }
}
