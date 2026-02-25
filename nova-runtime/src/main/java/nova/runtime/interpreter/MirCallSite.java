package nova.runtime.interpreter;

/**
 * 预解析的方法调用站点，缓存在 {@link com.novalang.ir.mir.MirInst#cache} 中
 * 避免每次 INVOKE_VIRTUAL/INVOKE_STATIC 执行时重复字符串解析。
 *
 * <p>INVOKE_VIRTUAL extra 格式: {@code "owner|methodName|descriptor;named:positionalCount:key1,key2"}<br>
 * INVOKE_STATIC  extra 格式: {@code "owner|methodName|descriptor"}</p>
 */
final class MirCallSite {

    final String owner;       // nullable
    final String methodName;
    final String namedInfo;   // nullable, only for INVOKE_VIRTUAL

    /** 单态内联缓存：上次成功解析的接收者类 */
    NovaClass cachedClass;
    /** 单态内联缓存：上次成功解析的方法 */
    NovaCallable cachedMethod;

    /** INVOKE_STATIC 缓存：已解析的 MirCallable（消除 HashMap 查找） */
    MirCallable resolvedCallable;
    /** 是否可走 fastCall 路径（无 this、无捕获、非 init） */
    boolean fastCallEligible;

    MirCallSite(String owner, String methodName, String namedInfo) {
        this.owner = owner;
        this.methodName = methodName;
        this.namedInfo = namedInfo;
    }

    /** 从 INVOKE_VIRTUAL 的 extra 字符串解析 */
    static MirCallSite parseVirtual(String extra) {
        String namedInfo = null;
        int semiColon = extra.indexOf(';');
        if (semiColon >= 0) {
            namedInfo = extra.substring(semiColon + 1);
            extra = extra.substring(0, semiColon);
        }
        return parseCore(extra, namedInfo);
    }

    /** 从 INVOKE_STATIC 的 extra 字符串解析 */
    static MirCallSite parseStatic(String extra) {
        return parseCore(extra, null);
    }

    private static MirCallSite parseCore(String extra, String namedInfo) {
        String owner = null;
        String methodName;
        int firstPipe = extra.indexOf('|');
        if (firstPipe >= 0) {
            owner = extra.substring(0, firstPipe);
            int secondPipe = extra.indexOf('|', firstPipe + 1);
            methodName = secondPipe >= 0
                    ? extra.substring(firstPipe + 1, secondPipe)
                    : extra.substring(firstPipe + 1);
        } else {
            methodName = extra;
        }
        return new MirCallSite(owner, methodName, namedInfo);
    }
}
