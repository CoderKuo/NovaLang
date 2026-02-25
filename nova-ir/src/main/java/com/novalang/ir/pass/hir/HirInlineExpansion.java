package com.novalang.ir.pass.hir;

import com.novalang.compiler.ast.AstNode;
import com.novalang.compiler.ast.Modifier;
import com.novalang.compiler.ast.expr.*;
import com.novalang.compiler.ast.expr.Literal.LiteralKind;
import com.novalang.ir.hir.*;
import com.novalang.ir.hir.decl.*;
import com.novalang.ir.hir.expr.*;
import com.novalang.compiler.hirtype.*;
import com.novalang.ir.pass.HirPass;

import java.util.*;
import java.util.stream.Collectors;

/**
 * HIR 层 reified 类型参数特化 Pass。
 * <p>
 * 对于 inline fun &lt;reified T&gt; 函数，在每个调用点收集具体类型参数，
 * 为每种类型参数组合生成特化函数副本（将 T 替换为实际类型），
 * 然后重写调用点指向特化版本。
 */
public class HirInlineExpansion implements HirPass {

    @Override
    public String getName() {
        return "HirInlineExpansion";
    }

    @Override
    public HirModule run(HirModule module) {
        // 1. 收集含 reified 类型参数的 inline 函数
        Map<String, HirFunction> inlineFunctions = collectInlineFunctions(module);
        if (inlineFunctions.isEmpty()) return module;

        // 2. 收集调用点的类型参数组合
        Map<String, Set<List<String>>> callSiteTypeArgs = collectCallSiteTypeArgs(module, inlineFunctions);
        if (callSiteTypeArgs.isEmpty()) return module;

        // 3. 生成特化副本
        List<HirDecl> specializedFunctions = generateSpecializations(inlineFunctions, callSiteTypeArgs);

        // 4. 重写调用点
        CallRewriter rewriter = new CallRewriter(inlineFunctions);
        HirModule rewritten = (HirModule) rewriter.transform(module);

        // 5. 扫描重写后的模块，检查哪些原 inline 函数仍有未特化的引用
        Set<String> stillReferenced = findRemainingReferences(rewritten, inlineFunctions.keySet());

        // 6. 仅对仍被引用的 inline 函数做 fallback 处理（reified T → Any）
        if (!stillReferenced.isEmpty()) {
            Map<String, HirFunction> remaining = new LinkedHashMap<>();
            for (String name : stillReferenced) {
                remaining.put(name, inlineFunctions.get(name));
            }
            FallbackRewriter fallbackRewriter = new FallbackRewriter(remaining);
            rewritten = (HirModule) fallbackRewriter.transform(rewritten);
        }

        // 7. 移除已完全特化的原 inline 函数声明（释放 M+1 中的冗余副本）
        Set<String> fullySpecialized = new HashSet<>(inlineFunctions.keySet());
        fullySpecialized.removeAll(stillReferenced);

        List<HirDecl> allDecls;
        if (!fullySpecialized.isEmpty()) {
            allDecls = new ArrayList<>();
            for (HirDecl decl : rewritten.getDeclarations()) {
                if (decl instanceof HirFunction
                        && fullySpecialized.contains(((HirFunction) decl).getName())) {
                    continue; // 跳过完全特化的原函数
                }
                allDecls.add(decl);
            }
        } else {
            allDecls = new ArrayList<>(rewritten.getDeclarations());
        }

        // 8. 将特化函数加入 module
        allDecls.addAll(specializedFunctions);
        return new HirModule(rewritten.getLocation(), rewritten.getPackageName(),
                rewritten.getImports(), allDecls);
    }

    // ==================== 收集 inline 函数 ====================

    private Map<String, HirFunction> collectInlineFunctions(HirModule module) {
        Map<String, HirFunction> result = new LinkedHashMap<>();
        for (HirDecl decl : module.getDeclarations()) {
            if (decl instanceof HirFunction) {
                HirFunction fn = (HirFunction) decl;
                if (fn.isInline() && fn.hasReifiedTypeParams()) {
                    result.put(fn.getName(), fn);
                }
            }
        }
        return result;
    }

    // ==================== 收集调用点类型参数 ====================

    private Map<String, Set<List<String>>> collectCallSiteTypeArgs(
            HirModule module, Map<String, HirFunction> inlineFunctions) {
        CallSiteCollector collector = new CallSiteCollector(inlineFunctions);
        collector.transform(module);
        return collector.getCallSiteTypeArgs();
    }

    private static class CallSiteCollector extends HirTransformer {
        private final Map<String, HirFunction> inlineFunctions;
        private final Map<String, Set<List<String>>> callSiteTypeArgs = new LinkedHashMap<>();

        CallSiteCollector(Map<String, HirFunction> inlineFunctions) {
            this.inlineFunctions = inlineFunctions;
        }

        Map<String, Set<List<String>>> getCallSiteTypeArgs() {
            return callSiteTypeArgs;
        }

        @Override
        public HirNode visitCall(HirCall node, Void ctx) {
            // 先递归子节点
            HirNode result = super.visitCall(node, ctx);
            // 检查是否是对 inline 函数的调用
            if (node.getCallee() instanceof Identifier) {
                String name = ((Identifier) node.getCallee()).getName();
                if (inlineFunctions.containsKey(name)
                        && node.getTypeArgs() != null && !node.getTypeArgs().isEmpty()) {
                    List<String> typeArgNames = node.getTypeArgs().stream()
                            .map(HirInlineExpansion::getTypeName)
                            .collect(Collectors.toList());
                    callSiteTypeArgs
                            .computeIfAbsent(name, k -> new LinkedHashSet<>())
                            .add(typeArgNames);
                }
            }
            return result;
        }
    }

    // ==================== 生成特化副本 ====================

    private List<HirDecl> generateSpecializations(
            Map<String, HirFunction> inlineFunctions,
            Map<String, Set<List<String>>> callSiteTypeArgs) {
        List<HirDecl> result = new ArrayList<>();

        for (Map.Entry<String, Set<List<String>>> entry : callSiteTypeArgs.entrySet()) {
            String funcName = entry.getKey();
            HirFunction originalFn = inlineFunctions.get(funcName);
            if (originalFn == null) continue;

            List<String> reifiedParams = new ArrayList<>(originalFn.getReifiedTypeParams());

            for (List<String> typeArgNames : entry.getValue()) {
                // 构建类型映射：reifiedParam → 具体类型
                Map<String, HirType> typeMapping = new LinkedHashMap<>();
                List<String> allTypeParams = originalFn.getTypeParams();
                int reifiedIndex = 0;
                for (int i = 0; i < allTypeParams.size(); i++) {
                    String paramName = allTypeParams.get(i);
                    if (originalFn.getReifiedTypeParams().contains(paramName)) {
                        if (reifiedIndex < typeArgNames.size()) {
                            typeMapping.put(paramName, new ClassType(typeArgNames.get(reifiedIndex)));
                        }
                        reifiedIndex++;
                    }
                }

                if (typeMapping.isEmpty()) continue;

                // 深克隆 + 类型替换
                TypeSubstTransformer subst = new TypeSubstTransformer(typeMapping);
                AstNode newBody = subst.transformBody(originalFn.getBody());

                // 特化函数名
                String specializedName = buildSpecializedName(funcName, typeArgNames);

                // 移除已替换的 typeParams
                List<String> remainingTypeParams = new ArrayList<>();
                for (String tp : allTypeParams) {
                    if (!typeMapping.containsKey(tp)) {
                        remainingTypeParams.add(tp);
                    }
                }

                // 移除 INLINE 修饰符
                Set<Modifier> newModifiers = new HashSet<>(originalFn.getModifiers());
                newModifiers.remove(Modifier.INLINE);

                // 参数也需要类型替换
                List<HirParam> newParams = new ArrayList<>();
                for (HirParam param : originalFn.getParams()) {
                    HirType paramType = param.getType();
                    if (paramType != null) {
                        paramType = substituteType(paramType, typeMapping);
                    }
                    newParams.add(new HirParam(param.getLocation(), param.getName(),
                            paramType, param.getDefaultValue(), param.isVararg()));
                }

                // 返回类型替换
                HirType newReturnType = originalFn.getReturnType() != null
                        ? substituteType(originalFn.getReturnType(), typeMapping)
                        : null;

                result.add(new HirFunction(
                        originalFn.getLocation(), specializedName, newModifiers,
                        originalFn.getAnnotations(), remainingTypeParams,
                        originalFn.getReceiverType(), newParams, newReturnType,
                        newBody, false, null, Collections.emptySet()));
            }
        }
        return result;
    }

    // ==================== TypeSubstTransformer ====================

    private static class TypeSubstTransformer extends HirTransformer {
        private final Map<String, HirType> typeMapping;

        TypeSubstTransformer(Map<String, HirType> typeMapping) {
            this.typeMapping = typeMapping;
        }

        private HirType substituteType(HirType type) {
            return HirInlineExpansion.substituteType(type, typeMapping);
        }

        @Override
        protected Expression transformExpr(Expression expr) {
            if (expr instanceof TypeCheckExpr) {
                TypeCheckExpr node = (TypeCheckExpr) expr;
                Expression operand = transformExpr(node.getOperand());
                HirType newTarget = substituteType(node.getHirTargetType());
                if (operand == node.getOperand() && newTarget == node.getHirTargetType()) return expr;
                TypeCheckExpr result = new TypeCheckExpr(node.getLocation(),
                        operand, node.getTargetType(), node.isNegated());
                result.setHirType(node.getHirType());
                result.setHirTargetType(newTarget);
                return result;
            }
            if (expr instanceof TypeCastExpr) {
                TypeCastExpr node = (TypeCastExpr) expr;
                Expression operand = transformExpr(node.getOperand());
                HirType newTarget = substituteType(node.getHirTargetType());
                if (operand == node.getOperand() && newTarget == node.getHirTargetType()) return expr;
                TypeCastExpr result = new TypeCastExpr(node.getLocation(),
                        operand, node.getTargetType(), node.isSafe());
                result.setHirType(node.getHirType());
                result.setHirTargetType(newTarget);
                return result;
            }
            if (expr instanceof MethodRefExpr) {
                MethodRefExpr node = (MethodRefExpr) expr;
                // T::class → 字符串字面量
                if ("class".equals(node.getMethodName()) && node.getTarget() instanceof Identifier) {
                    String varName = ((Identifier) node.getTarget()).getName();
                    HirType replacement = typeMapping.get(varName);
                    if (replacement instanceof ClassType) {
                        String typeName = ((ClassType) replacement).getName();
                        return new Literal(node.getLocation(),
                                new ClassType("String"), typeName, LiteralKind.STRING);
                    }
                }
            }
            return super.transformExpr(expr);
        }
    }

    // ==================== 重写调用点 ====================

    private static class CallRewriter extends HirTransformer {
        private final Map<String, HirFunction> inlineFunctions;

        CallRewriter(Map<String, HirFunction> inlineFunctions) {
            this.inlineFunctions = inlineFunctions;
        }

        @Override
        public HirNode visitCall(HirCall node, Void ctx) {
            HirCall call = (HirCall) super.visitCall(node, ctx);
            if (call.getCallee() instanceof Identifier) {
                String name = ((Identifier) call.getCallee()).getName();
                if (inlineFunctions.containsKey(name)
                        && call.getTypeArgs() != null && !call.getTypeArgs().isEmpty()) {
                    List<String> typeArgNames = call.getTypeArgs().stream()
                            .map(HirInlineExpansion::getTypeName)
                            .collect(Collectors.toList());
                    String specializedName = buildSpecializedName(name, typeArgNames);
                    Identifier newCallee = new Identifier(
                            call.getCallee().getLocation(), specializedName);
                    newCallee.setHirType(call.getCallee().getType());
                    return new HirCall(call.getLocation(), call.getType(),
                            newCallee, Collections.emptyList(), call.getArgs(),
                            call.hasNamedArgs() ? call.getNamedArgs() : null);
                }
            }
            return call;
        }
    }

    // ==================== Fallback 重写 ====================

    private static class FallbackRewriter extends HirTransformer {
        private final Map<String, HirFunction> inlineFunctions;

        FallbackRewriter(Map<String, HirFunction> inlineFunctions) {
            this.inlineFunctions = inlineFunctions;
        }

        @Override
        public HirNode visitFunction(HirFunction node, Void ctx) {
            if (inlineFunctions.containsKey(node.getName()) && node.hasReifiedTypeParams()) {
                // 将 reified 参数映射为 Any
                Map<String, HirType> fallbackMapping = new LinkedHashMap<>();
                for (String reifiedParam : node.getReifiedTypeParams()) {
                    fallbackMapping.put(reifiedParam, new ClassType("Any"));
                }
                TypeSubstTransformer subst = new TypeSubstTransformer(fallbackMapping);
                AstNode newBody = subst.transformBody(node.getBody());
                if (newBody == node.getBody()) return node;
                return new HirFunction(node.getLocation(), node.getName(),
                        node.getModifiers(), node.getAnnotations(), node.getTypeParams(),
                        node.getReceiverType(), node.getParams(), node.getReturnType(),
                        newBody, node.isConstructor(), node.getDelegationArgs(),
                        Collections.emptySet());
            }
            return super.visitFunction(node, ctx);
        }
    }

    // ==================== 引用扫描 ====================

    /**
     * 扫描模块中是否仍有对原 inline 函数名的引用（不含函数自身声明）。
     */
    private Set<String> findRemainingReferences(HirModule module, Set<String> funcNames) {
        Set<String> referenced = new HashSet<>();
        RefScanner scanner = new RefScanner(funcNames, referenced);
        for (HirDecl decl : module.getDeclarations()) {
            if (decl instanceof HirFunction && funcNames.contains(((HirFunction) decl).getName())) {
                continue; // 跳过函数自身声明
            }
            scanner.transform(decl);
        }
        return referenced;
    }

    private static class RefScanner extends HirTransformer {
        private final Set<String> funcNames;
        private final Set<String> referenced;

        RefScanner(Set<String> funcNames, Set<String> referenced) {
            this.funcNames = funcNames;
            this.referenced = referenced;
        }

        @Override
        protected Expression transformExpr(Expression expr) {
            if (expr instanceof Identifier) {
                String name = ((Identifier) expr).getName();
                if (funcNames.contains(name)) {
                    referenced.add(name);
                }
                return expr;
            }
            return super.transformExpr(expr);
        }
    }

    // ==================== 辅助方法 ====================

    static String getTypeName(HirType type) {
        if (type instanceof ClassType) {
            return ((ClassType) type).getName();
        }
        if (type instanceof PrimitiveType) {
            PrimitiveType.Kind kind = ((PrimitiveType) type).getKind();
            switch (kind) {
                case INT: return "Int";
                case LONG: return "Long";
                case FLOAT: return "Float";
                case DOUBLE: return "Double";
                case BOOLEAN: return "Boolean";
                case CHAR: return "Char";
                default: return kind.name();
            }
        }
        return type.toString();
    }

    private static String buildSpecializedName(String funcName, List<String> typeArgNames) {
        return funcName + "$$" + String.join("_", typeArgNames);
    }

    private static HirType substituteType(HirType type, Map<String, HirType> typeMapping) {
        if (type instanceof ClassType) {
            ClassType ct = (ClassType) type;
            HirType replacement = typeMapping.get(ct.getName());
            if (replacement != null && ct.getTypeArgs().isEmpty()) {
                return ct.isNullable()
                        ? replacement.withNullable(true) : replacement;
            }
        }
        return type;
    }
}
