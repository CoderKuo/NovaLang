package nova.runtime.interpreter.stdlib;
import nova.runtime.*;

import nova.runtime.interpreter.*;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Collections;

/**
 * nova.time — 时间日期操作
 */
public final class StdlibTime {

    private StdlibTime() {}

    public static void register(Environment env, Interpreter interp) {
        // 顶层函数
        env.defineVal("now", NovaNativeFunction.create("now", () ->
            NovaLong.of(System.currentTimeMillis())));

        env.defineVal("nowNanos", NovaNativeFunction.create("nowNanos", () ->
            NovaLong.of(System.nanoTime())));

        env.defineVal("sleep", NovaNativeFunction.create("sleep", (millis) -> {
            try { Thread.sleep(millis.asLong()); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return NovaNull.UNIT;
        }));

        // DateTime 命名空间
        NovaMap dateTimeNs = new NovaMap();

        dateTimeNs.put(NovaString.of("now"), NovaNativeFunction.create("now", () ->
            createDateTimeMap(LocalDateTime.now())));

        dateTimeNs.put(NovaString.of("of"), new NovaNativeFunction("of", -1, (interpreter, args) -> {
            int year = args.get(0).asInt();
            int month = args.size() > 1 ? args.get(1).asInt() : 1;
            int day = args.size() > 2 ? args.get(2).asInt() : 1;
            int hour = args.size() > 3 ? args.get(3).asInt() : 0;
            int minute = args.size() > 4 ? args.get(4).asInt() : 0;
            int second = args.size() > 5 ? args.get(5).asInt() : 0;
            return createDateTimeMap(LocalDateTime.of(year, month, day, hour, minute, second));
        }));

        dateTimeNs.put(NovaString.of("parse"), NovaNativeFunction.create("parse", (str) -> {
            try {
                return createDateTimeMap(LocalDateTime.parse(str.asString()));
            } catch (Exception e) {
                try {
                    return createDateTimeMap(LocalDate.parse(str.asString()).atStartOfDay());
                } catch (Exception e2) {
                    throw new NovaRuntimeException("Cannot parse datetime: " + str.asString());
                }
            }
        }));

        env.defineVal("DateTime", dateTimeNs);

        // Duration 命名空间
        NovaMap durationNs = new NovaMap();

        durationNs.put(NovaString.of("ofMillis"), NovaNativeFunction.create("ofMillis", (millis) ->
            createDurationMap(Duration.ofMillis(millis.asLong()))));

        durationNs.put(NovaString.of("ofSeconds"), NovaNativeFunction.create("ofSeconds", (secs) ->
            createDurationMap(Duration.ofSeconds(secs.asLong()))));

        durationNs.put(NovaString.of("ofMinutes"), NovaNativeFunction.create("ofMinutes", (mins) ->
            createDurationMap(Duration.ofMinutes(mins.asLong()))));

        durationNs.put(NovaString.of("ofHours"), NovaNativeFunction.create("ofHours", (hours) ->
            createDurationMap(Duration.ofHours(hours.asLong()))));

        durationNs.put(NovaString.of("ofDays"), NovaNativeFunction.create("ofDays", (days) ->
            createDurationMap(Duration.ofDays(days.asLong()))));

        env.defineVal("Duration", durationNs);
    }

    public static NovaMap createDateTimeMap(LocalDateTime dt) {
        NovaMap map = new NovaMap();
        map.put(NovaString.of("year"), NovaInt.of(dt.getYear()));
        map.put(NovaString.of("month"), NovaInt.of(dt.getMonthValue()));
        map.put(NovaString.of("day"), NovaInt.of(dt.getDayOfMonth()));
        map.put(NovaString.of("hour"), NovaInt.of(dt.getHour()));
        map.put(NovaString.of("minute"), NovaInt.of(dt.getMinute()));
        map.put(NovaString.of("second"), NovaInt.of(dt.getSecond()));
        map.put(NovaString.of("dayOfWeek"), NovaInt.of(dt.getDayOfWeek().getValue()));
        map.put(NovaString.of("dayOfYear"), NovaInt.of(dt.getDayOfYear()));
        map.put(NovaString.of("timestamp"), NovaLong.of(dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()));

        map.put(NovaString.of("plusDays"), NovaNativeFunction.create("plusDays", (n) ->
            createDateTimeMap(dt.plusDays(n.asLong()))));
        map.put(NovaString.of("plusHours"), NovaNativeFunction.create("plusHours", (n) ->
            createDateTimeMap(dt.plusHours(n.asLong()))));
        map.put(NovaString.of("plusMinutes"), NovaNativeFunction.create("plusMinutes", (n) ->
            createDateTimeMap(dt.plusMinutes(n.asLong()))));
        map.put(NovaString.of("plusSeconds"), NovaNativeFunction.create("plusSeconds", (n) ->
            createDateTimeMap(dt.plusSeconds(n.asLong()))));
        map.put(NovaString.of("minusDays"), NovaNativeFunction.create("minusDays", (n) ->
            createDateTimeMap(dt.minusDays(n.asLong()))));

        map.put(NovaString.of("format"), NovaNativeFunction.create("format", (pattern) ->
            NovaString.of(dt.format(DateTimeFormatter.ofPattern(pattern.asString())))));

        map.put(NovaString.of("isBefore"), NovaNativeFunction.create("isBefore", (other) -> {
            NovaMap otherMap = (NovaMap) other;
            LocalDateTime otherDt = extractDateTime(otherMap);
            return NovaBoolean.of(dt.isBefore(otherDt));
        }));

        map.put(NovaString.of("isAfter"), NovaNativeFunction.create("isAfter", (other) -> {
            NovaMap otherMap = (NovaMap) other;
            LocalDateTime otherDt = extractDateTime(otherMap);
            return NovaBoolean.of(dt.isAfter(otherDt));
        }));

        map.put(NovaString.of("durationTo"), NovaNativeFunction.create("durationTo", (other) -> {
            NovaMap otherMap = (NovaMap) other;
            LocalDateTime otherDt = extractDateTime(otherMap);
            return createDurationMap(Duration.between(dt, otherDt));
        }));

        map.put(NovaString.of("toString"), NovaNativeFunction.create("toString", () ->
            NovaString.of(dt.toString())));

        return map;
    }

    public static LocalDateTime extractDateTime(NovaMap map) {
        return LocalDateTime.of(
            map.get(NovaString.of("year")).asInt(),
            map.get(NovaString.of("month")).asInt(),
            map.get(NovaString.of("day")).asInt(),
            map.get(NovaString.of("hour")).asInt(),
            map.get(NovaString.of("minute")).asInt(),
            map.get(NovaString.of("second")).asInt()
        );
    }

    public static NovaMap createDurationMap(Duration dur) {
        NovaMap map = new NovaMap();
        map.put(NovaString.of("totalMillis"), NovaLong.of(dur.toMillis()));
        map.put(NovaString.of("totalSeconds"), NovaLong.of(dur.getSeconds()));
        map.put(NovaString.of("days"), NovaLong.of(dur.toDays()));
        map.put(NovaString.of("hours"), NovaLong.of(dur.toHours()));
        map.put(NovaString.of("minutes"), NovaLong.of(dur.toMinutes()));
        map.put(NovaString.of("seconds"), NovaLong.of(dur.getSeconds() % 60));
        map.put(NovaString.of("toString"), NovaNativeFunction.create("toString", () ->
            NovaString.of(dur.toString())));
        return map;
    }
}
