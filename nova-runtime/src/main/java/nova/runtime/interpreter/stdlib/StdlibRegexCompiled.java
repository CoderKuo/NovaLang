package nova.runtime.interpreter.stdlib;

import nova.runtime.*;
import nova.runtime.interpreter.NovaNativeFunction;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * nova.text 模块的编译模式运行时实现。
 *
 * <p>Regex 构造函数返回 NovaMap，方法通过 NovaDynamic 分派。</p>
 */
public final class StdlibRegexCompiled {

    private StdlibRegexCompiled() {}

    public static Object Regex(Object pattern) {
        Pattern compiled = Pattern.compile(str(pattern));
        return createRegexMap(compiled);
    }

    private static NovaMap createRegexMap(Pattern pattern) {
        NovaMap regex = new NovaMap();
        regex.put(NovaString.of("pattern"), NovaString.of(pattern.pattern()));

        regex.put(NovaString.of("matches"), NovaNativeFunction.create("matches", (input) ->
                NovaBoolean.of(pattern.matcher(input.asString()).matches())));

        regex.put(NovaString.of("containsMatchIn"), NovaNativeFunction.create("containsMatchIn", (input) ->
                NovaBoolean.of(pattern.matcher(input.asString()).find())));

        regex.put(NovaString.of("find"), NovaNativeFunction.create("find", (input) -> {
            Matcher m = pattern.matcher(input.asString());
            if (!m.find()) return null;
            return createMatchResult(m);
        }));

        regex.put(NovaString.of("findAll"), NovaNativeFunction.create("findAll", (input) -> {
            Matcher m = pattern.matcher(input.asString());
            NovaList results = new NovaList();
            while (m.find()) results.add(createMatchResult(m));
            return results;
        }));

        regex.put(NovaString.of("replace"), NovaNativeFunction.create("replace", (input, replacement) ->
                NovaString.of(pattern.matcher(input.asString()).replaceAll(replacement.asString()))));

        regex.put(NovaString.of("replaceFirst"), NovaNativeFunction.create("replaceFirst", (input, replacement) ->
                NovaString.of(pattern.matcher(input.asString()).replaceFirst(replacement.asString()))));

        regex.put(NovaString.of("split"), NovaNativeFunction.create("split", (input) -> {
            String[] parts = pattern.split(input.asString());
            NovaList result = new NovaList();
            for (String part : parts) result.add(NovaString.of(part));
            return result;
        }));

        return regex;
    }

    private static NovaMap createMatchResult(Matcher m) {
        NovaMap result = new NovaMap();
        result.put(NovaString.of("value"), NovaString.of(m.group()));
        result.put(NovaString.of("start"), NovaInt.of(m.start()));
        result.put(NovaString.of("end"), NovaInt.of(m.end()));

        NovaList groups = new NovaList();
        for (int i = 0; i <= m.groupCount(); i++) {
            String g = m.group(i);
            groups.add(g != null ? NovaString.of(g) : NovaNull.NULL);
        }
        result.put(NovaString.of("groups"), groups);

        return result;
    }

    private static String str(Object o) {
        if (o instanceof String) return (String) o;
        return String.valueOf(o);
    }
}
