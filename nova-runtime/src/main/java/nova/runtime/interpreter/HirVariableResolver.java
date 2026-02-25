package nova.runtime.interpreter;

import com.novalang.compiler.ast.AstNode;
import com.novalang.compiler.ast.expr.*;
import com.novalang.compiler.ast.stmt.*;
import com.novalang.ir.hir.*;
import com.novalang.ir.hir.decl.*;
import com.novalang.ir.hir.expr.*;
import com.novalang.ir.hir.stmt.*;
import com.novalang.ir.pass.HirPass;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HIR 变量解析 pass：为每个 Identifier 计算 (depth, slot) 索引对。
 * 运行时可直接用 Environment.getAtSlot(depth, slot) 访问变量，零字符串比较。
 *
 * 只解析函数/Lambda 内部的变量——模块顶层变量由于全局 environment 有大量
 * 内置函数/常量（slot 布局不可预测），不进行槽位解析，运行时走字符串回退。
 *
 * 未解析的变量（模块级、隐式 this 字段、内置函数等）保持 resolvedSlot = -1。
 */
public class HirVariableResolver implements HirPass {

    private final ScopeTracker<Void> scopes = new ScopeTracker<>();

    @Override
    public String getName() {
        return "HirVariableResolver";
    }

    @Override
    public HirModule run(HirModule module) {
        // scopes 已初始化为空栈  // 模块级不创建 scope
        resolveModule(module);
        return module;
    }

    // ==================== Scope ====================


    private void pushScope() {
        scopes.beginScope();
    }

    private void popScope() {
        scopes.endScope();
    }

    // ==================== 顶层 ====================

    private void resolveModule(HirModule module) {
        // 模块级不创建 scope（全局 environment 有内置函数，slot 布局不可预测）
        // scopes 为空时，模块级变量引用不会被解析
        for (HirDecl decl : module.getDeclarations()) {
            resolveDecl(decl);
        }
    }

    // ==================== 声明 ====================

    private void resolveDecl(HirDecl decl) {
        if (decl instanceof HirFunction) {
            resolveFunction((HirFunction) decl, false);
        } else if (decl instanceof HirField) {
            resolveField((HirField) decl);
        } else if (decl instanceof HirClass) {
            resolveClass((HirClass) decl);
        }
    }

    /**
     * 解析函数内部变量。
     * @param isMethod true 表示类方法/构造器，运行时会定义 "this" 在 slot 0
     */
    private void resolveFunction(HirFunction func, boolean isMethod) {
        // default value 在外层作用域中解析
        for (HirParam param : func.getParams()) {
            if (param.hasDefaultValue()) {
                resolveExpr(param.getDefaultValue());
            }
        }
        // delegation args 在外层作用域中解析
        if (func.hasDelegation()) {
            for (Expression arg : func.getDelegationArgs()) {
                resolveExpr(arg);
            }
        }

        // 函数体创建新作用域（parent=null 隔离模块级）
        pushScope();
        if (isMethod || func.isExtensionFunction()) {
            // 类方法/构造器/扩展函数：executeBoundMethod 先定义 "this"
            scopes.defineVariable("this");
        }
        for (HirParam param : func.getParams()) {
            scopes.defineVariable(param.getName());
        }

        AstNode body = func.getBody();
        if (body != null) {
            resolveBody(body);
        }
        popScope();
    }

    private void resolveLambda(HirLambda lambda) {
        pushScope();
        // "this" 始终定义在 slot 0，与运行时两条执行路径保持一致：
        //   - executeHirLambdaBody: this = 闭包中的 this（继承外层方法的 receiver）
        //   - executeBoundMethod lambda: this = 绑定的新 receiver
        scopes.defineVariable("this");
        List<HirParam> params = lambda.getParams();
        for (HirParam param : params) {
            scopes.defineVariable(param.getName());
        }
        // 无参 lambda 隐式定义 "it"
        if (params.isEmpty()) {
            scopes.defineVariable("it");
        }

        AstNode body = lambda.getBody();
        if (body != null) {
            resolveBody(body);
        }
        popScope();
    }

    /**
     * 解析函数/lambda 的 body。
     * 当 body 是非透明 Block 时，内联到当前作用域（与运行时块内联一致）。
     */
    private void resolveBody(AstNode body) {
        if (body instanceof Block && !((Block) body).isTransparent()) {
            // 内联：在当前函数作用域中直接解析语句
            for (Statement stmt : ((Block) body).getStatements()) {
                resolveStmt(stmt);
            }
        } else if (body instanceof Statement) {
            resolveStmt((Statement) body);
        } else if (body instanceof Expression) {
            resolveExpr((Expression) body);
        }
    }

    private void resolveField(HirField field) {
        if (field.hasInitializer()) {
            resolveExpr(field.getInitializer());
        }
    }

    private void resolveClass(HirClass cls) {
        // 类方法和构造器：isMethod=true（运行时通过 executeBoundMethod 调用）
        for (HirFunction method : cls.getMethods()) {
            resolveFunction(method, true);
        }
        for (HirFunction ctor : cls.getConstructors()) {
            resolveFunction(ctor, true);
        }
        // 字段初始化器
        for (HirField field : cls.getFields()) {
            resolveField(field);
        }
        // 枚举条目
        for (HirEnumEntry entry : cls.getEnumEntries()) {
            for (Expression arg : entry.getArgs()) {
                resolveExpr(arg);
            }
            for (HirDecl member : entry.getMembers()) {
                resolveDecl(member);
            }
        }
        // 超类构造器参数
        for (Expression arg : cls.getSuperConstructorArgs()) {
            resolveExpr(arg);
        }
    }

    // ==================== 语句 ====================

    private void resolveStmt(Statement stmt) {
        if (stmt instanceof Block) {
            resolveBlock((Block) stmt);
        } else if (stmt instanceof ExpressionStmt) {
            resolveExpr(((ExpressionStmt) stmt).getExpression());
        } else if (stmt instanceof HirDeclStmt) {
            HirDecl decl = ((HirDeclStmt) stmt).getDeclaration();
            if (decl instanceof HirField) {
                HirField field = (HirField) decl;
                if (field.hasInitializer()) {
                    resolveExpr(field.getInitializer());
                }
                if (scopes.hasCurrentScope()) {
                    scopes.defineVariable(field.getName());
                }
            } else if (decl instanceof HirFunction) {
                if (scopes.hasCurrentScope()) {
                    scopes.defineVariable(decl.getName());
                }
                resolveFunction((HirFunction) decl, false);
            } else if (decl instanceof HirClass) {
                if (scopes.hasCurrentScope()) {
                    scopes.defineVariable(decl.getName());
                }
                resolveClass((HirClass) decl);
            } else {
                resolveDecl(decl);
            }
        } else if (stmt instanceof IfStmt) {
            IfStmt ifStmt = (IfStmt) stmt;
            resolveExpr(ifStmt.getCondition());
            resolveStmt(ifStmt.getThenBranch());
            if (ifStmt.hasElse()) {
                resolveStmt(ifStmt.getElseBranch());
            }
        } else if (stmt instanceof ForStmt) {
            resolveFor((ForStmt) stmt);
        } else if (stmt instanceof HirLoop) {
            HirLoop loop = (HirLoop) stmt;
            resolveExpr(loop.getCondition());
            resolveStmt(loop.getBody());
        } else if (stmt instanceof HirTry) {
            HirTry tryStmt = (HirTry) stmt;
            resolveStmt(tryStmt.getTryBlock());
            for (HirTry.CatchClause cc : tryStmt.getCatches()) {
                pushScope();
                if (scopes.hasCurrentScope()) {
                    scopes.defineVariable(cc.getParamName());
                }
                resolveStmt(cc.getBody());
                popScope();
            }
            if (tryStmt.hasFinally()) {
                resolveStmt(tryStmt.getFinallyBlock());
            }
        } else if (stmt instanceof ReturnStmt) {
            ReturnStmt ret = (ReturnStmt) stmt;
            if (ret.hasValue()) {
                resolveExpr(ret.getValue());
            }
        } else if (stmt instanceof ThrowStmt) {
            resolveExpr(((ThrowStmt) stmt).getException());
        }
    }

    private void resolveBlock(Block block) {
        if (block.isTransparent()) {
            for (Statement stmt : block.getStatements()) {
                resolveStmt(stmt);
            }
        } else {
            pushScope();
            for (Statement stmt : block.getStatements()) {
                resolveStmt(stmt);
            }
            popScope();
        }
    }

    private void resolveFor(ForStmt node) {
        resolveExpr(node.getIterable());

        if (!scopes.hasCurrentScope()) {
            // 模块级 for 循环：不解析变量
            Statement body = node.getBody();
            if (body instanceof Block && !((Block) body).isTransparent()) {
                for (Statement stmt : ((Block) body).getStatements()) {
                    resolveStmt(stmt);
                }
            } else {
                resolveStmt(body);
            }
            return;
        }

        // 函数内 for 循环：创建新作用域
        pushScope();
        for (String var : node.getVariables()) {
            scopes.defineVariable(var);
        }

        Statement body = node.getBody();
        if (body instanceof Block && !((Block) body).isTransparent()) {
            for (Statement stmt : ((Block) body).getStatements()) {
                resolveStmt(stmt);
            }
        } else {
            resolveStmt(body);
        }
        popScope();
    }

    // ==================== 表达式 ====================

    private void resolveExpr(Expression expr) {
        if (expr == null) return;

        if (expr instanceof Identifier) {
            Identifier ref = (Identifier) expr;
            if (scopes.hasCurrentScope()) {
                int[] result = scopes.resolveVariable(ref.getName());
                if (result != null) {
                    ref.setResolved(result[0], result[1]);
                }
            }
        } else if (expr instanceof BinaryExpr) {
            BinaryExpr bin = (BinaryExpr) expr;
            resolveExpr(bin.getLeft());
            resolveExpr(bin.getRight());
        } else if (expr instanceof UnaryExpr) {
            resolveExpr(((UnaryExpr) expr).getOperand());
        } else if (expr instanceof HirCall) {
            HirCall call = (HirCall) expr;
            resolveExpr(call.getCallee());
            for (Expression arg : call.getArgs()) {
                resolveExpr(arg);
            }
            for (Expression namedArg : call.getNamedArgs().values()) {
                resolveExpr(namedArg);
            }
        } else if (expr instanceof MemberExpr) {
            resolveExpr(((MemberExpr) expr).getTarget());
        } else if (expr instanceof IndexExpr) {
            IndexExpr idx = (IndexExpr) expr;
            resolveExpr(idx.getTarget());
            resolveExpr(idx.getIndex());
        } else if (expr instanceof AssignExpr) {
            AssignExpr assign = (AssignExpr) expr;
            resolveExpr(assign.getValue());
            resolveAssignTarget(assign.getTarget());
        } else if (expr instanceof HirLambda) {
            resolveLambda((HirLambda) expr);
        } else if (expr instanceof RangeExpr) {
            RangeExpr range = (RangeExpr) expr;
            resolveExpr(range.getStart());
            resolveExpr(range.getEnd());
            if (range.hasStep()) {
                resolveExpr(range.getStep());
            }
        } else if (expr instanceof HirCollectionLiteral) {
            for (Expression elem : ((HirCollectionLiteral) expr).getElements()) {
                resolveExpr(elem);
            }
        } else if (expr instanceof HirObjectLiteral) {
            HirObjectLiteral obj = (HirObjectLiteral) expr;
            for (Expression arg : obj.getSuperConstructorArgs()) {
                resolveExpr(arg);
            }
            for (HirDecl member : obj.getMembers()) {
                resolveDecl(member);
            }
        } else if (expr instanceof ConditionalExpr) {
            ConditionalExpr cond = (ConditionalExpr) expr;
            resolveExpr(cond.getCondition());
            resolveExpr(cond.getThenExpr());
            resolveExpr(cond.getElseExpr());
        } else if (expr instanceof BlockExpr) {
            BlockExpr blockExpr = (BlockExpr) expr;
            for (Statement stmt : blockExpr.getStatements()) {
                resolveStmt(stmt);
            }
            resolveExpr(blockExpr.getResult());
        } else if (expr instanceof TypeCheckExpr) {
            resolveExpr(((TypeCheckExpr) expr).getOperand());
        } else if (expr instanceof TypeCastExpr) {
            resolveExpr(((TypeCastExpr) expr).getOperand());
        } else if (expr instanceof NotNullExpr) {
            resolveExpr(((NotNullExpr) expr).getOperand());
        } else if (expr instanceof ErrorPropagationExpr) {
            resolveExpr(((ErrorPropagationExpr) expr).getOperand());
        } else if (expr instanceof AwaitExpr) {
            resolveExpr(((AwaitExpr) expr).getOperand());
        } else if (expr instanceof MethodRefExpr) {
            MethodRefExpr ref = (MethodRefExpr) expr;
            if (ref.hasTarget()) {
                resolveExpr(ref.getTarget());
            }
        } else if (expr instanceof HirNew) {
            for (Expression arg : ((HirNew) expr).getArgs()) {
                resolveExpr(arg);
            }
        }
    }

    private void resolveAssignTarget(Expression target) {
        if (target instanceof Identifier) {
            Identifier ref = (Identifier) target;
            if (scopes.hasCurrentScope()) {
                int[] result = scopes.resolveVariable(ref.getName());
                if (result != null) {
                    ref.setResolved(result[0], result[1]);
                }
            }
        } else if (target instanceof MemberExpr) {
            resolveExpr(((MemberExpr) target).getTarget());
        } else if (target instanceof IndexExpr) {
            IndexExpr idx = (IndexExpr) target;
            resolveExpr(idx.getTarget());
            resolveExpr(idx.getIndex());
        }
    }
}
