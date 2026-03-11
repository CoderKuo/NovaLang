package nova.runtime.interpreter;

import nova.runtime.NovaCallable;
import nova.runtime.types.NovaClass;

/**
 * ?????????????? MirInst.cache ??
 */
final class MirCallSite {

    final String owner;
    final String methodName;
    final String namedInfo;

    NovaClass cachedClass;
    NovaCallable cachedMethod;
    MirCallable cachedDirectMethod;
    byte cachedDirectArity = -1;
    boolean scalarizedPlusEligible;
    byte scalarizedFieldCount;

    MirCallable resolvedCallable;
    boolean fastCallEligible;

    MirCallSite(String owner, String methodName, String namedInfo) {
        this.owner = owner;
        this.methodName = methodName;
        this.namedInfo = namedInfo;
    }

    static MirCallSite parseVirtual(String extra) {
        String namedInfo = null;
        int semiColon = extra.indexOf(';');
        if (semiColon >= 0) {
            namedInfo = extra.substring(semiColon + 1);
            extra = extra.substring(0, semiColon);
        }
        return parseCore(extra, namedInfo);
    }

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
