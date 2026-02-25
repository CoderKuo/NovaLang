package com.novalang.compiler.analysis;

import com.novalang.compiler.ast.SourceLocation;

/**
 * 语义诊断条目
 */
public final class SemanticDiagnostic {

    public enum Severity {
        ERROR, WARNING, INFO, HINT
    }

    private final Severity severity;
    private final String message;
    private final SourceLocation location;
    private final int length;

    public SemanticDiagnostic(Severity severity, String message, SourceLocation location, int length) {
        this.severity = severity;
        this.message = message;
        this.location = location;
        this.length = length;
    }

    public Severity getSeverity() { return severity; }
    public String getMessage() { return message; }
    public SourceLocation getLocation() { return location; }
    public int getLength() { return length; }
}
