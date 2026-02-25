# NovaLang AST 设计

本文档定义 NovaLang 的抽象语法树（AST）节点结构。

---

## 1. 设计原则

1. **不可变性**：所有 AST 节点为不可变对象（字段 final，无 setter）
2. **类型安全**：使用抽象类 + 枚举类型标记确保类型安全
3. **位置信息**：每个节点携带源码位置
4. **JDK 8 全面兼容**：
   - 不使用 `var`、Records、Sealed Classes
   - 不使用 `List.of()` 等 JDK 9+ API
   - 使用传统的 class 继承体系

---

## 2. 基础结构

### 2.1 源码位置

```java
public final class SourceLocation {
    private final String file;
    private final int line;
    private final int column;
    private final int offset;
    private final int length;

    public SourceLocation(String file, int line, int column, int offset, int length) {
        this.file = file;
        this.line = line;
        this.column = column;
        this.offset = offset;
        this.length = length;
    }

    // getters...

    public static final SourceLocation UNKNOWN = new SourceLocation("<unknown>", 0, 0, 0, 0);
}
```

### 2.2 AST 节点基类

```java
public abstract class AstNode {
    protected final SourceLocation location;

    protected AstNode(SourceLocation location) {
        this.location = location;
    }

    public SourceLocation getLocation() {
        return location;
    }

    public abstract <R, C> R accept(AstVisitor<R, C> visitor, C context);
}
```

### 2.3 访问者接口

```java
public interface AstVisitor<R, C> {
    // 声明
    R visitProgram(Program node, C ctx);
    R visitPackageDecl(PackageDecl node, C ctx);
    R visitImportDecl(ImportDecl node, C ctx);
    R visitClassDecl(ClassDecl node, C ctx);
    R visitInterfaceDecl(InterfaceDecl node, C ctx);
    R visitObjectDecl(ObjectDecl node, C ctx);
    R visitEnumDecl(EnumDecl node, C ctx);
    R visitFunDecl(FunDecl node, C ctx);
    R visitPropertyDecl(PropertyDecl node, C ctx);
    R visitTypeAliasDecl(TypeAliasDecl node, C ctx);

    // 语句
    R visitBlock(Block node, C ctx);
    R visitExpressionStmt(ExpressionStmt node, C ctx);
    R visitIfStmt(IfStmt node, C ctx);
    R visitWhenStmt(WhenStmt node, C ctx);
    R visitForStmt(ForStmt node, C ctx);
    R visitWhileStmt(WhileStmt node, C ctx);
    R visitDoWhileStmt(DoWhileStmt node, C ctx);
    R visitTryStmt(TryStmt node, C ctx);
    R visitReturnStmt(ReturnStmt node, C ctx);
    R visitBreakStmt(BreakStmt node, C ctx);
    R visitContinueStmt(ContinueStmt node, C ctx);
    R visitThrowStmt(ThrowStmt node, C ctx);
    R visitGuardStmt(GuardStmt node, C ctx);
    R visitUseStmt(UseStmt node, C ctx);

    // 表达式
    R visitBinaryExpr(BinaryExpr node, C ctx);
    R visitUnaryExpr(UnaryExpr node, C ctx);
    R visitCallExpr(CallExpr node, C ctx);
    R visitIndexExpr(IndexExpr node, C ctx);
    R visitMemberExpr(MemberExpr node, C ctx);
    R visitAssignExpr(AssignExpr node, C ctx);
    R visitLambdaExpr(LambdaExpr node, C ctx);
    R visitIfExpr(IfExpr node, C ctx);
    R visitWhenExpr(WhenExpr node, C ctx);
    R visitTryExpr(TryExpr node, C ctx);
    R visitAsyncExpr(AsyncExpr node, C ctx);
    R visitAwaitExpr(AwaitExpr node, C ctx);
    R visitIdentifier(Identifier node, C ctx);
    R visitLiteral(Literal node, C ctx);
    R visitThisExpr(ThisExpr node, C ctx);
    R visitSuperExpr(SuperExpr node, C ctx);
    R visitTypeCheckExpr(TypeCheckExpr node, C ctx);
    R visitTypeCastExpr(TypeCastExpr node, C ctx);
    R visitRangeExpr(RangeExpr node, C ctx);
    R visitSliceExpr(SliceExpr node, C ctx);
    R visitSpreadExpr(SpreadExpr node, C ctx);
    R visitPipelineExpr(PipelineExpr node, C ctx);
    R visitMethodRefExpr(MethodRefExpr node, C ctx);
    R visitObjectLiteralExpr(ObjectLiteralExpr node, C ctx);
    R visitCollectionLiteral(CollectionLiteral node, C ctx);
    R visitStringInterpolation(StringInterpolation node, C ctx);
    R visitPlaceholderExpr(PlaceholderExpr node, C ctx);
    R visitElvisExpr(ElvisExpr node, C ctx);
    R visitSafeCallExpr(SafeCallExpr node, C ctx);
    R visitNotNullExpr(NotNullExpr node, C ctx);
    R visitErrorPropagationExpr(ErrorPropagationExpr node, C ctx);

    // 类型
    R visitSimpleType(SimpleType node, C ctx);
    R visitNullableType(NullableType node, C ctx);
    R visitFunctionType(FunctionType node, C ctx);
    R visitGenericType(GenericType node, C ctx);
}
```

---

## 3. 声明节点

### 3.1 程序

```java
public final class Program extends AstNode {
    private final PackageDecl packageDecl;        // 可选
    private final List<ImportDecl> imports;
    private final List<Declaration> declarations;

    // constructor, getters, accept...
}
```

### 3.2 包与导入

```java
public final class PackageDecl extends AstNode {
    private final QualifiedName name;
}

public final class ImportDecl extends AstNode {
    private final boolean isStatic;
    private final QualifiedName name;
    private final boolean isWildcard;           // import java.util.*
    private final String alias;                 // import Foo as Bar
}

public final class QualifiedName extends AstNode {
    private final List<String> parts;

    public String getFullName() {
        return String.join(".", parts);
    }
}
```

### 3.3 类声明

```java
public abstract class Declaration extends AstNode {
    protected final List<Annotation> annotations;
    protected final List<Modifier> modifiers;
    protected final String name;
}

public final class ClassDecl extends Declaration {
    private final List<TypeParameter> typeParams;
    private final List<Parameter> primaryConstructorParams;  // 可选
    private final List<TypeRef> superTypes;
    private final List<Declaration> members;
    private final boolean isSealed;
    private final boolean isAbstract;
    private final boolean isOpen;
}

public final class InterfaceDecl extends Declaration {
    private final List<TypeParameter> typeParams;
    private final List<TypeRef> superTypes;
    private final List<Declaration> members;
}

public final class ObjectDecl extends Declaration {
    private final List<TypeRef> superTypes;
    private final List<Declaration> members;
    private final boolean isCompanion;
}

public final class EnumDecl extends Declaration {
    private final List<Parameter> primaryConstructorParams;
    private final List<TypeRef> superTypes;
    private final List<EnumEntry> entries;
    private final List<Declaration> members;
}

public final class EnumEntry extends AstNode {
    private final String name;
    private final List<Expression> args;
    private final List<Declaration> members;
}
```

### 3.4 函数声明

```java
public final class FunDecl extends Declaration {
    private final List<TypeParameter> typeParams;
    private final TypeRef receiverType;          // 扩展函数接收者
    private final List<Parameter> params;
    private final TypeRef returnType;            // 可选，默认 Unit
    private final AstNode body;                  // Block 或 Expression
    private final boolean isInline;
    private final boolean isOperator;
    private final boolean isSuspend;
}

public final class Parameter extends AstNode {
    private final List<Annotation> annotations;
    private final String name;
    private final TypeRef type;
    private final Expression defaultValue;       // 可选
    private final boolean isVararg;
}
```

### 3.5 属性声明

```java
public final class PropertyDecl extends Declaration {
    private final boolean isVal;                 // true=val, false=var
    private final List<TypeParameter> typeParams;
    private final TypeRef receiverType;          // 扩展属性
    private final TypeRef type;                  // 可选，可推断
    private final Expression initializer;        // 可选
    private final PropertyAccessor getter;       // 可选
    private final PropertyAccessor setter;       // 可选
    private final boolean isConst;
    private final boolean isLazy;
}

public final class PropertyAccessor extends AstNode {
    private final boolean isGetter;
    private final List<Modifier> modifiers;
    private final Parameter param;               // setter 参数
    private final AstNode body;                  // Block 或 Expression
}
```

### 3.6 类型别名

```java
public final class TypeAliasDecl extends Declaration {
    private final List<TypeParameter> typeParams;
    private final TypeRef aliasedType;
}
```

---

## 4. 语句节点

```java
public abstract class Statement extends AstNode {}

public final class Block extends Statement {
    private final List<Statement> statements;
}

public final class ExpressionStmt extends Statement {
    private final Expression expression;
}

public final class IfStmt extends Statement {
    private final Expression condition;
    private final String bindingName;            // if-let: if (val x = expr)
    private final Statement thenBranch;
    private final Statement elseBranch;          // 可选
}

public final class WhenStmt extends Statement {
    private final Expression subject;            // 可选
    private final List<WhenBranch> branches;
    private final Statement elseBranch;          // 可选
}

public final class WhenBranch extends AstNode {
    private final List<WhenCondition> conditions;
    private final Statement body;                // Block 或 ExpressionStmt
}

public abstract class WhenCondition extends AstNode {}

public final class ExpressionCondition extends WhenCondition {
    private final Expression expression;
}

public final class TypeCondition extends WhenCondition {
    private final TypeRef type;
    private final boolean negated;               // !is
}

public final class RangeCondition extends WhenCondition {
    private final Expression range;
    private final boolean negated;               // !in
}

public final class ForStmt extends Statement {
    private final String label;                  // 可选
    private final List<String> variables;        // 支持解构
    private final Expression iterable;
    private final Statement body;
}

public final class WhileStmt extends Statement {
    private final String label;
    private final Expression condition;
    private final Statement body;
}

public final class DoWhileStmt extends Statement {
    private final String label;
    private final Statement body;
    private final Expression condition;
}

public final class TryStmt extends Statement {
    private final Block tryBlock;
    private final List<CatchClause> catchClauses;
    private final Block finallyBlock;            // 可选
}

public final class CatchClause extends AstNode {
    private final String paramName;
    private final TypeRef paramType;
    private final Block body;
}

public final class ReturnStmt extends Statement {
    private final Expression value;              // 可选
    private final String label;                  // return@label
}

public final class BreakStmt extends Statement {
    private final String label;
}

public final class ContinueStmt extends Statement {
    private final String label;
}

public final class ThrowStmt extends Statement {
    private final Expression exception;
}

public final class GuardStmt extends Statement {
    private final String bindingName;
    private final Expression expression;
    private final Statement elseBody;            // return/throw/break/continue
}

public final class UseStmt extends Statement {
    private final List<UseBinding> bindings;
    private final Block body;
}

public final class UseBinding extends AstNode {
    private final String name;
    private final Expression initializer;
}
```

---

## 5. 表达式节点

```java
public abstract class Expression extends AstNode {
    // 类型信息（语义分析后填充）
    protected TypeRef resolvedType;

    public TypeRef getResolvedType() {
        return resolvedType;
    }

    public void setResolvedType(TypeRef type) {
        this.resolvedType = type;
    }
}

// === 字面量 ===

public final class Literal extends Expression {
    private final Object value;
    private final LiteralKind kind;

    public enum LiteralKind {
        INT, LONG, FLOAT, DOUBLE, CHAR, STRING, BOOLEAN, NULL
    }
}

public final class Identifier extends Expression {
    private final String name;
}

public final class ThisExpr extends Expression {
    private final String label;                  // this@Outer
}

public final class SuperExpr extends Expression {
    private final String label;
}

// === 二元表达式 ===

public final class BinaryExpr extends Expression {
    private final Expression left;
    private final BinaryOp operator;
    private final Expression right;

    public enum BinaryOp {
        // 算术
        ADD, SUB, MUL, DIV, MOD,
        // 比较
        EQ, NE, REF_EQ, REF_NE, LT, GT, LE, GE,
        // 逻辑
        AND, OR,
        // 范围
        RANGE_INCLUSIVE, RANGE_EXCLUSIVE,
        // 包含
        IN, NOT_IN
    }
}

// === 一元表达式 ===

public final class UnaryExpr extends Expression {
    private final UnaryOp operator;
    private final Expression operand;
    private final boolean isPrefix;

    public enum UnaryOp {
        NEG, POS, NOT, INC, DEC
    }
}

// === 调用表达式 ===

public final class CallExpr extends Expression {
    private final Expression callee;
    private final List<TypeRef> typeArgs;
    private final List<Argument> args;
    private final LambdaExpr trailingLambda;     // 尾随 Lambda
}

public final class Argument extends AstNode {
    private final String name;                   // 命名参数
    private final Expression value;
    private final boolean isSpread;              // *args
}

// === 索引表达式 ===

public final class IndexExpr extends Expression {
    private final Expression target;
    private final Expression index;
}

// === 切片表达式 ===

public final class SliceExpr extends Expression {
    private final Expression target;
    private final Expression start;              // 可选
    private final Expression end;                // 可选
    private final boolean isEndExclusive;        // ..<
}

// === 成员访问 ===

public final class MemberExpr extends Expression {
    private final Expression target;
    private final String member;
}

// === 安全调用 ===

public final class SafeCallExpr extends Expression {
    private final Expression target;
    private final String member;
    private final List<Argument> args;           // 可选，如果是方法调用
}

// === 非空断言 ===

public final class NotNullExpr extends Expression {
    private final Expression operand;
}

// === 错误传播 ===

public final class ErrorPropagationExpr extends Expression {
    private final Expression operand;
}

// === Elvis 表达式 ===

public final class ElvisExpr extends Expression {
    private final Expression left;
    private final Expression right;
}

// === 赋值表达式 ===

public final class AssignExpr extends Expression {
    private final Expression target;
    private final AssignOp operator;
    private final Expression value;

    public enum AssignOp {
        ASSIGN,             // =
        ADD_ASSIGN,         // +=
        SUB_ASSIGN,         // -=
        MUL_ASSIGN,         // *=
        DIV_ASSIGN,         // /=
        MOD_ASSIGN,         // %=
        NULL_COALESCE,      // ??=
        OR_ASSIGN,          // ||=
        AND_ASSIGN          // &&=
    }
}

// === 类型操作 ===

public final class TypeCheckExpr extends Expression {
    private final Expression operand;
    private final TypeRef type;
    private final boolean negated;               // !is
}

public final class TypeCastExpr extends Expression {
    private final Expression operand;
    private final TypeRef type;
    private final boolean isSafe;                // as?
}

// === Lambda 表达式 ===

public final class LambdaExpr extends Expression {
    private final List<LambdaParam> params;
    private final AstNode body;                  // Block 或 Expression

    public boolean hasExplicitParams() {
        return !params.isEmpty();
    }
}

public final class LambdaParam extends AstNode {
    private final String name;
    private final TypeRef type;                  // 可选
}

// === 条件表达式 ===

public final class IfExpr extends Expression {
    private final Expression condition;
    private final String bindingName;            // if-let
    private final Expression thenExpr;
    private final Expression elseExpr;
}

public final class WhenExpr extends Expression {
    private final Expression subject;
    private final List<WhenBranch> branches;
    private final Expression elseExpr;
}

// === Try 表达式 ===

public final class TryExpr extends Expression {
    private final Block tryBlock;
    private final List<CatchClause> catchClauses;
    private final Block finallyBlock;
}

// === 异步表达式 ===

public final class AsyncExpr extends Expression {
    private final Block body;
}

public final class AwaitExpr extends Expression {
    private final Expression operand;
}

// === 范围表达式 ===

public final class RangeExpr extends Expression {
    private final Expression start;
    private final Expression end;
    private final Expression step;               // 可选
    private final boolean isEndExclusive;        // ..<
}

// === 管道表达式 ===

public final class PipelineExpr extends Expression {
    private final Expression left;
    private final Expression right;
}

// === 方法引用 ===

public final class MethodRefExpr extends Expression {
    private final Expression target;             // 可选，类型或实例
    private final TypeRef typeTarget;            // 可选，Class::method
    private final String methodName;
    private final boolean isConstructor;         // ::new
}

// === 匿名对象 ===

public final class ObjectLiteralExpr extends Expression {
    private final List<TypeRef> superTypes;
    private final List<Declaration> members;
}

// === 集合字面量 ===

public final class CollectionLiteral extends Expression {
    private final CollectionKind kind;
    private final List<Expression> elements;
    private final List<MapEntry> mapEntries;     // 仅 MAP

    public enum CollectionKind {
        LIST, SET, MAP
    }
}

public final class MapEntry extends AstNode {
    private final Expression key;
    private final Expression value;
}

// === 字符串插值 ===

public final class StringInterpolation extends Expression {
    private final List<StringPart> parts;
}

public abstract class StringPart extends AstNode {}

public final class StringLiteralPart extends StringPart {
    private final String value;
}

public final class StringExprPart extends StringPart {
    private final Expression expression;
}

// === 占位符 ===

public final class PlaceholderExpr extends Expression {
    // 用于部分应用: f(_, x)
}

// === Spread 表达式 ===

public final class SpreadExpr extends Expression {
    private final Expression operand;
}
```

---

## 6. 类型节点

```java
public abstract class TypeRef extends AstNode {
    // 语义分析后解析的实际类型
    protected Type resolvedType;
}

public final class SimpleType extends TypeRef {
    private final QualifiedName name;
}

public final class NullableType extends TypeRef {
    private final TypeRef innerType;
}

public final class GenericType extends TypeRef {
    private final QualifiedName name;
    private final List<TypeArgument> typeArgs;
}

public final class TypeArgument extends AstNode {
    private final Variance variance;             // in/out/无
    private final TypeRef type;                  // 或 * 通配符

    public enum Variance {
        INVARIANT, IN, OUT
    }
}

public final class FunctionType extends TypeRef {
    private final TypeRef receiverType;          // 可选
    private final List<TypeRef> paramTypes;
    private final TypeRef returnType;
    private final boolean isSuspend;
}

public final class TypeParameter extends AstNode {
    private final String name;
    private final Variance variance;
    private final TypeRef upperBound;            // : SomeType
}
```

---

## 7. 辅助结构

```java
public final class Annotation extends AstNode {
    private final String name;
    private final List<AnnotationArg> args;
}

public final class AnnotationArg extends AstNode {
    private final String name;                   // 可选
    private final Expression value;
}

public enum Modifier {
    PUBLIC, PRIVATE, PROTECTED, INTERNAL,
    ABSTRACT, SEALED, OPEN, FINAL,
    OVERRIDE, CONST, INLINE, CROSSINLINE, REIFIED,
    OPERATOR, VARARG, SUSPEND, STATIC
}
```

---

## 8. AST 工厂（Builder Pattern）

```java
public final class AstFactory {
    private SourceLocation loc;

    public AstFactory at(SourceLocation loc) {
        this.loc = loc;
        return this;
    }

    public Literal intLiteral(int value) {
        return new Literal(loc, value, Literal.LiteralKind.INT);
    }

    public Literal stringLiteral(String value) {
        return new Literal(loc, value, Literal.LiteralKind.STRING);
    }

    public Identifier identifier(String name) {
        return new Identifier(loc, name);
    }

    public BinaryExpr binary(Expression left, BinaryOp op, Expression right) {
        return new BinaryExpr(loc, left, op, right);
    }

    public CallExpr call(Expression callee, List<Argument> args) {
        return new CallExpr(loc, callee, Collections.emptyList(), args, null);
    }

    // ... 更多工厂方法
}
```

---

## 9. AST 打印（调试用）

```java
public final class AstPrinter implements AstVisitor<String, Integer> {

    public String print(AstNode node) {
        return node.accept(this, 0);
    }

    @Override
    public String visitBinaryExpr(BinaryExpr node, Integer indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent(indent)).append("BinaryExpr(").append(node.getOperator()).append(")\n");
        sb.append(node.getLeft().accept(this, indent + 1));
        sb.append(node.getRight().accept(this, indent + 1));
        return sb.toString();
    }

    // ... 其他 visit 方法

    private String indent(int level) {
        return "  ".repeat(level);
    }
}
```

---

*NovaLang AST 设计 v1.0*
