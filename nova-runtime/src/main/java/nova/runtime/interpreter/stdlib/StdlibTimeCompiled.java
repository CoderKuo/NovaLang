package nova.runtime.interpreter.stdlib;

import nova.runtime.*;
import nova.runtime.interpreter.NovaRuntimeException;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * nova.time 模块的编译模式运行时实现。
 *
 * <p>编译后的字节码通过 INVOKESTATIC 调用此类的静态方法。
 * 命名空间（DateTime、Duration）作为内部类，方法签名全部为 Object 参数/返回值。</p>
 *
 * <p>复用 {@link StdlibTime} 的 createDateTimeMap/createDurationMap 实现。</p>
 */
public final class StdlibTimeCompiled {

    private StdlibTimeCompiled() {}

    // ============ 顶层函数 ============

    public static Object now() {
        return NovaLong.of(System.currentTimeMillis());
    }

    public static Object nowNanos() {
        return NovaLong.of(System.nanoTime());
    }

    public static Object sleep(Object millis) {
        try {
            Thread.sleep(((NovaValue) millis).asLong());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return NovaNull.UNIT;
    }

    // ============ DateTime 命名空间 ============

    public static class DateTime {

        public static Object now() {
            return StdlibTime.createDateTimeMap(LocalDateTime.now());
        }

        // 使用重载而非 varargs，确保 findJavaStaticMethod 按参数数量匹配
        public static Object of(Object year) {
            return ofImpl(val(year), 1, 1, 0, 0, 0);
        }

        public static Object of(Object year, Object month) {
            return ofImpl(val(year), val(month), 1, 0, 0, 0);
        }

        public static Object of(Object year, Object month, Object day) {
            return ofImpl(val(year), val(month), val(day), 0, 0, 0);
        }

        public static Object of(Object year, Object month, Object day, Object hour) {
            return ofImpl(val(year), val(month), val(day), val(hour), 0, 0);
        }

        public static Object of(Object year, Object month, Object day,
                                 Object hour, Object minute) {
            return ofImpl(val(year), val(month), val(day), val(hour), val(minute), 0);
        }

        public static Object of(Object year, Object month, Object day,
                                 Object hour, Object minute, Object second) {
            return ofImpl(val(year), val(month), val(day), val(hour), val(minute), val(second));
        }

        private static Object ofImpl(int year, int month, int day, int hour, int minute, int second) {
            return StdlibTime.createDateTimeMap(
                    LocalDateTime.of(year, month, day, hour, minute, second));
        }

        private static int val(Object o) {
            return ((NovaValue) o).asInt();
        }

        public static Object parse(Object str) {
            String s = ((NovaValue) str).asString();
            try {
                return StdlibTime.createDateTimeMap(LocalDateTime.parse(s));
            } catch (Exception e) {
                try {
                    return StdlibTime.createDateTimeMap(
                            java.time.LocalDate.parse(s).atStartOfDay());
                } catch (Exception e2) {
                    throw new NovaRuntimeException("Cannot parse datetime: " + s);
                }
            }
        }
    }

    // ============ Duration 命名空间 ============

    public static class DurationNs {

        public static Object ofMillis(Object millis) {
            return StdlibTime.createDurationMap(Duration.ofMillis(((NovaValue) millis).asLong()));
        }

        public static Object ofSeconds(Object secs) {
            return StdlibTime.createDurationMap(Duration.ofSeconds(((NovaValue) secs).asLong()));
        }

        public static Object ofMinutes(Object mins) {
            return StdlibTime.createDurationMap(Duration.ofMinutes(((NovaValue) mins).asLong()));
        }

        public static Object ofHours(Object hours) {
            return StdlibTime.createDurationMap(Duration.ofHours(((NovaValue) hours).asLong()));
        }

        public static Object ofDays(Object days) {
            return StdlibTime.createDurationMap(Duration.ofDays(((NovaValue) days).asLong()));
        }
    }
}
