package nova.runtime.interpreter.stdlib;
import nova.runtime.*;
import nova.runtime.types.Environment;

import nova.runtime.interpreter.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * nova.text — 正则表达式支持
 */
public final class StdlibRegex {

    private StdlibRegex() {}

    public static void register(Environment env, Interpreter interp) {
        // RegexOption 常量
        NovaMap regexOption = new NovaMap();
        regexOption.put(NovaString.of("IGNORE_CASE"), NovaString.of("i"));
        regexOption.put(NovaString.of("MULTILINE"), NovaString.of("m"));
        regexOption.put(NovaString.of("DOT_MATCHES_ALL"), NovaString.of("s"));
        env.defineVal("RegexOption", regexOption);

        // Regex(pattern) 或 Regex(pattern, options)
        env.defineVal("Regex", new NovaNativeFunction("Regex", -1, (interpreter, args) -> {
            String pattern = args.get(0).asString();
            int flags = 0;
            if (args.size() > 1) {
                String options = args.get(1).asString();
                if (options.contains("i")) flags |= Pattern.CASE_INSENSITIVE;
                if (options.contains("m")) flags |= Pattern.MULTILINE;
                if (options.contains("s")) flags |= Pattern.DOTALL;
            }
            Pattern compiled = Pattern.compile(pattern, flags);
            return createRegexObject(compiled);
        }));
    }

    private static NovaMap createRegexObject(Pattern pattern) {
        NovaMap regex = new NovaMap();
        regex.put(NovaString.of("pattern"), NovaString.of(pattern.pattern()));

        regex.put(NovaString.of("matches"), NovaNativeFunction.create("matches", (input) ->
            NovaBoolean.of(pattern.matcher(input.asString()).matches())));

        regex.put(NovaString.of("containsMatchIn"), NovaNativeFunction.create("containsMatchIn", (input) ->
            NovaBoolean.of(pattern.matcher(input.asString()).find())));

        regex.put(NovaString.of("find"), NovaNativeFunction.create("find", (input) -> {
            Matcher m = pattern.matcher(input.asString());
            if (!m.find()) return NovaNull.NULL;
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

        NovaList groupValues = new NovaList();
        for (int i = 0; i <= m.groupCount(); i++) {
            String g = m.group(i);
            groupValues.add(g != null ? NovaString.of(g) : NovaString.of(""));
        }
        result.put(NovaString.of("groupValues"), groupValues);

        return result;
    }
}
