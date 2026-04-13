package com.novalang.runtime;

import java.io.PrintStream;

import com.novalang.runtime.NovaException.ErrorKind;

/**
 * 统一输出工具类。
 *
 * <p>编译路径和解释器路径共用此类输出，支持通过 ThreadLocal 重定向 stdout。
 * 解释器通过 {@link #setOut(PrintStream)} 设置自定义输出流，
 * 编译后的字节码通过 INVOKESTATIC 调用 {@link #println}/{@link #print}。</p>
 */
public final class NovaPrint {

    private NovaPrint() {}

    /** 可重定向的输出流（默认 System.out） */
    private static final ThreadLocal<PrintStream> OUT = new ThreadLocal<>();
    /** stdio 是否被安全策略禁止 */
    private static final ThreadLocal<Boolean> MUTED = new ThreadLocal<>();

    /** 获取当前输出流 */
    public static PrintStream getOut() {
        PrintStream ps = OUT.get();
        return ps != null ? ps : System.out;
    }

    /** 设置输出流（解释器在初始化时调用） */
    public static void setOut(PrintStream out) {
        OUT.set(out);
    }

    /** 禁止输出（安全策略） */
    public static void mute() {
        MUTED.set(Boolean.TRUE);
    }

    /** 清除自定义输出流（恢复为 System.out） */
    public static void resetOut() {
        OUT.remove();
        MUTED.remove();
    }

    private static boolean isMuted() {
        return Boolean.TRUE.equals(MUTED.get());
    }

    // ============ 编译路径 INVOKESTATIC 目标方法 ============

    /** println() 无参 */
    public static void println() {
        if (isMuted()) throw new NovaException(ErrorKind.ACCESS_DENIED, "安全策略禁止标准输出");
        getOut().println();
    }

    /** println(value) 单参 */
    public static void println(Object value) {
        if (isMuted()) throw new NovaException(ErrorKind.ACCESS_DENIED, "安全策略禁止标准输出");
        getOut().println(asString(value));
    }

    /** print(value) 单参 */
    public static void print(Object value) {
        if (isMuted()) throw new NovaException(ErrorKind.ACCESS_DENIED, "安全策略禁止标准输出");
        getOut().print(asString(value));
    }

    /** println(多参) 空格分隔 */
    public static void printlnVarargs(Object... args) {
        PrintStream out = getOut();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(asString(args[i]));
        }
        out.println(sb.toString());
    }

    /** print(多参) 空格分隔 */
    public static void printVarargs(Object... args) {
        PrintStream out = getOut();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(asString(args[i]));
        }
        out.print(sb.toString());
    }

    private static String asString(Object value) {
        if (value instanceof NovaValue) return ((NovaValue) value).asString();
        return String.valueOf(value);
    }
}
