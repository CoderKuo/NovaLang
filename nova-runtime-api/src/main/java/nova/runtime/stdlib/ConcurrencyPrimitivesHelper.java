package nova.runtime.stdlib;

import nova.runtime.NovaType;

import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 编译路径的并发原语辅助类。
 *
 * <p>编译器将 {@code AtomicInt(0)} 编译为
 * {@code INVOKESTATIC ConcurrencyPrimitivesHelper.atomicInt([Object])Object}。</p>
 */
public final class ConcurrencyPrimitivesHelper {

    private ConcurrencyPrimitivesHelper() {}

    private static final ConcurrentHashMap<Class<?>, Method> invokeCache = new ConcurrentHashMap<>();

    // ============ 工厂方法（vararg 入口） ============

    /** AtomicInt(initial) */
    public static Object atomicInt(Object[] args) {
        if (args.length != 1) throw new RuntimeException("AtomicInt expects 1 argument, got " + args.length);
        return new CompileAtomicInt(((Number) args[0]).intValue());
    }

    /** AtomicLong(initial) */
    public static Object atomicLong(Object[] args) {
        if (args.length != 1) throw new RuntimeException("AtomicLong expects 1 argument, got " + args.length);
        return new CompileAtomicLong(((Number) args[0]).longValue());
    }

    /** AtomicRef(initial) */
    public static Object atomicRef(Object[] args) {
        if (args.length != 1) throw new RuntimeException("AtomicRef expects 1 argument, got " + args.length);
        return new CompileAtomicRef(args[0]);
    }

    /** Channel() 或 Channel(capacity) */
    public static Object channel(Object[] args) {
        int capacity = args.length == 0 ? Integer.MAX_VALUE : ((Number) args[0]).intValue();
        return new CompileChannel(capacity);
    }

    /** Mutex() */
    public static Object mutex(Object[] args) {
        if (args.length != 0) throw new RuntimeException("Mutex expects 0 arguments, got " + args.length);
        return new CompileMutex();
    }

    // ============ 编译路径类型 ============

    @NovaType(name = "AtomicInt", description = "原子整数")
    public static final class CompileAtomicInt {
        private final AtomicInteger value;
        CompileAtomicInt(int initial) { this.value = new AtomicInteger(initial); }
        public int get() { return value.get(); }
        public void set(Object v) { value.set(((Number) v).intValue()); }
        public int incrementAndGet() { return value.incrementAndGet(); }
        public int decrementAndGet() { return value.decrementAndGet(); }
        public int addAndGet(Object v) { return value.addAndGet(((Number) v).intValue()); }
        public boolean compareAndSet(Object expect, Object update) {
            return value.compareAndSet(((Number) expect).intValue(), ((Number) update).intValue());
        }
        @Override public String toString() { return "AtomicInt(" + value.get() + ")"; }
    }

    @NovaType(name = "AtomicLong", description = "原子长整数")
    public static final class CompileAtomicLong {
        private final AtomicLong value;
        CompileAtomicLong(long initial) { this.value = new AtomicLong(initial); }
        public long get() { return value.get(); }
        public void set(Object v) { value.set(((Number) v).longValue()); }
        public long incrementAndGet() { return value.incrementAndGet(); }
        public long decrementAndGet() { return value.decrementAndGet(); }
        public long addAndGet(Object v) { return value.addAndGet(((Number) v).longValue()); }
        public boolean compareAndSet(Object expect, Object update) {
            return value.compareAndSet(((Number) expect).longValue(), ((Number) update).longValue());
        }
        @Override public String toString() { return "AtomicLong(" + value.get() + ")"; }
    }

    @NovaType(name = "AtomicRef", description = "原子引用")
    public static final class CompileAtomicRef {
        private final AtomicReference<Object> ref;
        CompileAtomicRef(Object initial) { this.ref = new AtomicReference<>(initial); }
        public Object get() { return ref.get(); }
        public void set(Object v) { ref.set(v); }
        public boolean compareAndSet(Object expect, Object update) {
            return ref.compareAndSet(expect, update);
        }
        @Override public String toString() { return "AtomicRef(" + ref.get() + ")"; }
    }

    @NovaType(name = "Channel", description = "并发通道")
    public static final class CompileChannel implements Iterable<Object> {
        private final LinkedBlockingQueue<Object> queue;
        private volatile boolean closed = false;
        CompileChannel(int capacity) {
            this.queue = capacity == Integer.MAX_VALUE
                ? new LinkedBlockingQueue<>()
                : new LinkedBlockingQueue<>(capacity);
        }
        public void send(Object value) {
            if (closed) throw new RuntimeException("Cannot send to a closed channel");
            try { queue.put(value); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        public Object receive() {
            try { return queue.take(); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        public Object receiveTimeout(Object timeoutMs) {
            try {
                Object val = queue.poll(((Number) timeoutMs).longValue(), TimeUnit.MILLISECONDS);
                if (val == null) throw new RuntimeException("Channel receive timed out");
                return val;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        public Object tryReceive() { return queue.poll(); }
        public int size() { return queue.size(); }
        public boolean isEmpty() { return queue.isEmpty(); }
        public boolean isClosed() { return closed; }
        public void close() { closed = true; }
        @Override public String toString() { return "Channel(size=" + queue.size() + ")"; }

        @Override
        public Iterator<Object> iterator() {
            return new Iterator<Object>() {
                public boolean hasNext() { return !closed || !queue.isEmpty(); }
                public Object next() {
                    Object val = queue.poll();
                    if (val != null) return val;
                    if (closed) throw new NoSuchElementException("Channel closed");
                    try { return queue.take(); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new NoSuchElementException("interrupted");
                    }
                }
            };
        }
    }

    @NovaType(name = "Mutex", description = "互斥锁")
    public static final class CompileMutex {
        private final ReentrantLock lock = new ReentrantLock();
        public void lock() { lock.lock(); }
        public void unlock() { lock.unlock(); }
        public boolean tryLock() { return lock.tryLock(); }
        public boolean isLocked() { return lock.isLocked(); }
        public Object withLock(Object block) {
            lock.lock();
            try {
                return invoke0(block);
            } finally {
                lock.unlock();
            }
        }
        @Override public String toString() { return "Mutex(locked=" + lock.isLocked() + ")"; }
    }

    // ============ Lambda 调用辅助 ============

    private static Object invoke0(Object lambda) {
        try {
            Method m = invokeCache.computeIfAbsent(lambda.getClass(), cls -> {
                for (Method method : cls.getMethods()) {
                    if ("invoke".equals(method.getName()) && method.getParameterCount() == 0) return method;
                }
                for (Method method : cls.getMethods()) {
                    if ("invoke".equals(method.getName())) return method;
                }
                throw new RuntimeException("Lambda has no invoke() method: " + cls.getName());
            });
            return m.invoke(lambda);
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException(cause != null ? cause : e);
        }
    }
}
