package com.novalang.compiler.ast.stmt;

import com.novalang.compiler.ast.AstNode;
import com.novalang.compiler.ast.SourceLocation;

/**
 * 语句基类
 */
public abstract class Statement extends AstNode {

    protected Statement(SourceLocation location) {
        super(location);
    }
}
