package nova.runtime.stdlib;

/**
 * Character 类型扩展方法。
 */
@Ext("java/lang/Character")
public final class CharExtensions {

    private CharExtensions() {}

    public static Object isDigit(Object ch) {
        return Character.isDigit((Character) ch);
    }

    public static Object isLetter(Object ch) {
        return Character.isLetter((Character) ch);
    }

    public static Object isWhitespace(Object ch) {
        return Character.isWhitespace((Character) ch);
    }

    public static Object isUpperCase(Object ch) {
        return Character.isUpperCase((Character) ch);
    }

    public static Object isLowerCase(Object ch) {
        return Character.isLowerCase((Character) ch);
    }

    public static Object uppercase(Object ch) {
        return String.valueOf(Character.toUpperCase((Character) ch));
    }

    public static Object lowercase(Object ch) {
        return String.valueOf(Character.toLowerCase((Character) ch));
    }

    public static Object code(Object ch) {
        return (int) (char) (Character) ch;
    }

    public static Object digitToInt(Object ch) {
        char c = (Character) ch;
        if (c >= '0' && c <= '9') return c - '0';
        throw new RuntimeException("'" + c + "' is not a digit");
    }
}
