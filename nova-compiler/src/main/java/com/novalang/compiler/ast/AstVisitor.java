package com.novalang.compiler.ast;

import com.novalang.compiler.ast.decl.*;
import com.novalang.compiler.ast.expr.*;
import com.novalang.compiler.ast.stmt.*;
import com.novalang.compiler.ast.type.*;

/**
 * AST 访问者接口
 *
 * <p>所有方法提供默认实现（返回 null），实现类只需覆盖感兴趣的节点类型。</p>
 */
public interface AstVisitor<R, C> {

    // ============ 声明 ============

    default R visitProgram(Program node, C ctx) { return null; }

    default R visitPackageDecl(PackageDecl node, C ctx) { return null; }

    default R visitImportDecl(ImportDecl node, C ctx) { return null; }

    default R visitClassDecl(ClassDecl node, C ctx) { return null; }

    default R visitInterfaceDecl(InterfaceDecl node, C ctx) { return null; }

    default R visitObjectDecl(ObjectDecl node, C ctx) { return null; }

    default R visitEnumDecl(EnumDecl node, C ctx) { return null; }

    default R visitFunDecl(FunDecl node, C ctx) { return null; }

    default R visitPropertyDecl(PropertyDecl node, C ctx) { return null; }

    default R visitTypeAliasDecl(TypeAliasDecl node, C ctx) { return null; }

    default R visitParameter(Parameter node, C ctx) { return null; }

    default R visitQualifiedName(QualifiedName node, C ctx) { return null; }

    default R visitDestructuringDecl(DestructuringDecl node, C ctx) { return null; }

    default R visitConstructorDecl(ConstructorDecl node, C ctx) { return null; }

    default R visitInitBlockDecl(InitBlockDecl node, C ctx) { return null; }

    // ============ 语句 ============

    default R visitBlock(Block node, C ctx) { return null; }

    default R visitExpressionStmt(ExpressionStmt node, C ctx) { return null; }

    default R visitIfStmt(IfStmt node, C ctx) { return null; }

    default R visitWhenStmt(WhenStmt node, C ctx) { return null; }

    default R visitForStmt(ForStmt node, C ctx) { return null; }

    default R visitWhileStmt(WhileStmt node, C ctx) { return null; }

    default R visitDoWhileStmt(DoWhileStmt node, C ctx) { return null; }

    default R visitTryStmt(TryStmt node, C ctx) { return null; }

    default R visitReturnStmt(ReturnStmt node, C ctx) { return null; }

    default R visitBreakStmt(BreakStmt node, C ctx) { return null; }

    default R visitContinueStmt(ContinueStmt node, C ctx) { return null; }

    default R visitThrowStmt(ThrowStmt node, C ctx) { return null; }

    default R visitGuardStmt(GuardStmt node, C ctx) { return null; }

    default R visitUseStmt(UseStmt node, C ctx) { return null; }

    default R visitDeclarationStmt(DeclarationStmt node, C ctx) { return null; }

    // ============ 表达式 ============

    default R visitBinaryExpr(BinaryExpr node, C ctx) { return null; }

    default R visitUnaryExpr(UnaryExpr node, C ctx) { return null; }

    default R visitCallExpr(CallExpr node, C ctx) { return null; }

    default R visitIndexExpr(IndexExpr node, C ctx) { return null; }

    default R visitMemberExpr(MemberExpr node, C ctx) { return null; }

    default R visitAssignExpr(AssignExpr node, C ctx) { return null; }

    default R visitLambdaExpr(LambdaExpr node, C ctx) { return null; }

    default R visitIfExpr(IfExpr node, C ctx) { return null; }

    default R visitWhenExpr(WhenExpr node, C ctx) { return null; }

    default R visitTryExpr(TryExpr node, C ctx) { return null; }

    default R visitAwaitExpr(AwaitExpr node, C ctx) { return null; }

    default R visitIdentifier(Identifier node, C ctx) { return null; }

    default R visitLiteral(Literal node, C ctx) { return null; }

    default R visitThisExpr(ThisExpr node, C ctx) { return null; }

    default R visitSuperExpr(SuperExpr node, C ctx) { return null; }

    default R visitTypeCheckExpr(TypeCheckExpr node, C ctx) { return null; }

    default R visitTypeCastExpr(TypeCastExpr node, C ctx) { return null; }

    default R visitRangeExpr(RangeExpr node, C ctx) { return null; }

    default R visitSliceExpr(SliceExpr node, C ctx) { return null; }

    default R visitSpreadExpr(SpreadExpr node, C ctx) { return null; }

    default R visitPipelineExpr(PipelineExpr node, C ctx) { return null; }

    default R visitMethodRefExpr(MethodRefExpr node, C ctx) { return null; }

    default R visitObjectLiteralExpr(ObjectLiteralExpr node, C ctx) { return null; }

    default R visitCollectionLiteral(CollectionLiteral node, C ctx) { return null; }

    default R visitStringInterpolation(StringInterpolation node, C ctx) { return null; }

    default R visitPlaceholderExpr(PlaceholderExpr node, C ctx) { return null; }

    default R visitElvisExpr(ElvisExpr node, C ctx) { return null; }

    default R visitSafeCallExpr(SafeCallExpr node, C ctx) { return null; }

    default R visitSafeIndexExpr(SafeIndexExpr node, C ctx) { return null; }

    default R visitNotNullExpr(NotNullExpr node, C ctx) { return null; }

    default R visitErrorPropagationExpr(ErrorPropagationExpr node, C ctx) { return null; }

    default R visitScopeShorthandExpr(ScopeShorthandExpr node, C ctx) { return null; }

    default R visitJumpExpr(JumpExpr node, C ctx) { return null; }

    default R visitConditionalExpr(ConditionalExpr node, C ctx) { return null; }

    default R visitBlockExpr(BlockExpr node, C ctx) { return null; }

    // ============ 类型 ============

    default R visitSimpleType(SimpleType node, C ctx) { return null; }

    default R visitNullableType(NullableType node, C ctx) { return null; }

    default R visitFunctionType(FunctionType node, C ctx) { return null; }

    default R visitGenericType(GenericType node, C ctx) { return null; }
}
