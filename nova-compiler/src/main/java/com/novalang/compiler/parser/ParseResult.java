package com.novalang.compiler.parser;

import com.novalang.compiler.ast.decl.Program;
import com.novalang.compiler.ast.stmt.Statement;

import java.util.Collections;
import java.util.List;

/**
 * 容错解析的结果：包含部分 AST 和收集到的错误列表
 */
public final class ParseResult {
    private final Program program;
    private final List<ParseError> errors;
    private final List<Statement> topLevelStatements;

    public ParseResult(Program program, List<ParseError> errors) {
        this(program, errors, Collections.emptyList());
    }

    public ParseResult(Program program, List<ParseError> errors, List<Statement> topLevelStatements) {
        this.program = program;
        this.errors = errors;
        this.topLevelStatements = topLevelStatements;
    }

    public Program getProgram() {
        return program;
    }

    public List<ParseError> getErrors() {
        return errors;
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    /** 顶层非声明语句（仅由 parseTolerant 填充） */
    public List<Statement> getTopLevelStatements() {
        return topLevelStatements;
    }
}
