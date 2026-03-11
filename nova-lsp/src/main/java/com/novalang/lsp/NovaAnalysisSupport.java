package com.novalang.lsp;

import com.novalang.compiler.analysis.AnalysisResult;
import com.novalang.compiler.analysis.SemanticAnalyzer;
import com.novalang.compiler.ast.decl.Program;
import com.novalang.compiler.lexer.Lexer;
import com.novalang.compiler.parser.ParseResult;
import com.novalang.compiler.parser.Parser;

final class NovaAnalysisSupport {

    private NovaAnalysisSupport() {}

    static DocumentManager.CachedAnalysis analyze(String uri, String content) {
        if (content == null) {
            return null;
        }

        String fileName = DocumentManager.getFileName(uri);
        Lexer lexer = new Lexer(content, fileName);
        Parser parser = new Parser(lexer, fileName);
        ParseResult parseResult = parser.parseTolerant();

        SemanticAnalyzer analyzer = new SemanticAnalyzer();
        AnalysisResult analysisResult = analyzer.analyze(
                parseResult.getProgram(), parseResult.getTopLevelStatements());

        return new DocumentManager.CachedAnalysis(parseResult, analysisResult);
    }

    static String packageName(DocumentManager.CachedAnalysis cached) {
        if (cached == null || cached.parseResult == null) {
            return "";
        }
        return packageName(cached.parseResult.getProgram());
    }

    static String packageName(Program program) {
        if (program == null || program.getPackageDecl() == null || program.getPackageDecl().getName() == null) {
            return "";
        }
        return program.getPackageDecl().getName().getFullName();
    }
}
