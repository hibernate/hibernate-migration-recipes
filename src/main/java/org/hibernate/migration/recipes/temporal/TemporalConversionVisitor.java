package org.hibernate.migration.recipes.temporal;

import org.hibernate.migration.recipes.support.MigrationSupport;

import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.tree.*;
import java.util.*;

import org.hibernate.migration.recipes.table.SkippedMigrations;

/// Applies completed temporal decisions and reports their diagnostics without further analysis.
/// Only traversal-local state, such as generated parameter names, is mutable here.
/// @author Steve Ebersole
final class TemporalConversionVisitor extends MigrationSupport.JavaVisitor {
    private static final String TEMPORAL_FQN = "jakarta.persistence.Temporal";
    private static final String TEMPORAL_TYPE_FQN = "jakarta.persistence.TemporalType";
    private final TemporalConversionPlan plan;

    TemporalConversionVisitor(Recipe recipe, SkippedMigrations skipped, TemporalConversionPlan plan) {
        super(recipe, skipped);
        this.plan = plan;
    }

    private String parameter;
    @Override
    public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
        parameter = "temporalValue";
        String text = cu.printAll();
        while (text.contains(parameter)) parameter += "_";
        return super.visitCompilationUnit(cu, ctx);
    }

    @Override
    public J.Annotation visitAnnotation(J.Annotation a, ExecutionContext ctx) {
        TemporalConversionPlan.Property decision = plan.annotation(a.getId());
        if (decision != null && decision.diagnostic() != null) {
            TemporalConversionPlan.Diagnostic diagnostic = decision.diagnostic();
            skip(a, ctx, diagnostic.subject(), diagnostic.reason(), diagnostic.message());
        }
        return super.visitAnnotation(a, ctx);
    }

    @Override
    public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations vd, ExecutionContext ctx) {
        J.VariableDeclarations result = super.visitVariableDeclarations(vd, ctx);
        for (J.Annotation a : vd.getLeadingAnnotations()) {
            TemporalConversionPlan.Property c = plan.annotation(a.getId());
            if (c == null || !c.accepted()) continue;
            List<J.Annotation> annotations = new ArrayList<>(result.getLeadingAnnotations());
            annotations.removeIf(it -> it.getId().equals(a.getId()));
            List<Comment> comments = new ArrayList<>(result.getPrefix().getComments());
            new JavaIsoVisitor<List<Comment>>() {
                @Override public Space visitSpace(Space space, Space.Location loc, List<Comment> found) {
                    found.addAll(space.getComments());
                    return space;
                }
            }.visit(a, comments);
            Space prefix = result.getPrefix().withComments(comments);
            result = result.withLeadingAnnotations(annotations).withPrefix(prefix)
                    .withTypeExpression(((TypeTree) TypeTree.build(c.target())).withType(JavaType.ShallowClass.build(c.target()))
                            .withPrefix(result.getTypeExpression().getPrefix()));
            maybeRemoveImport(TEMPORAL_FQN);
            maybeRemoveImport(TEMPORAL_TYPE_FQN);
            maybeRemoveImport(TEMPORAL_TYPE_FQN + "." + plan.precision(a));
            maybeRemoveImport(c.source());
        }
        if (vd.getVariables().size() == 1) {
            JavaType.Variable variable = vd.getVariables().get(0).getVariableType();
            TemporalConversionPlan.Property c = plan.variable(variable);
            if (c != null && c.accepted() && result.getTypeExpression() != null
                    && !(result.getTypeExpression() instanceof J.Identifier && "var".equals(((J.Identifier) result.getTypeExpression()).getSimpleName())))
                result = result.withTypeExpression(((TypeTree) TypeTree.build(c.target())).withType(JavaType.ShallowClass.build(c.target())).withPrefix(result.getTypeExpression().getPrefix()));
        }
        return result;
    }

    private JavaType.Method convertedMethod(JavaType.Method original, TemporalConversionPlan.Accessor accessor) {
        JavaType type = JavaType.ShallowClass.build(accessor.property().target());
        return accessor.getter() ? original.withReturnType(type) : original.withParameterTypes(Collections.singletonList(type));
    }

    @Override public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration m, ExecutionContext ctx) {
        J.MethodDeclaration result = super.visitMethodDeclaration(m, ctx);
        TemporalConversionPlan.Accessor accessor = plan.accessor(m.getMethodType());
        if (accessor == null || !accessor.property().accepted()) return result;
        JavaType.Method converted = convertedMethod(m.getMethodType(), accessor);
        result = result.withMethodType(converted).withName(result.getName().withType(converted));
        if (accessor.getter()) {
            result = result.withReturnTypeExpression(((TypeTree) TypeTree.build(accessor.property().target()))
                    .withType(JavaType.ShallowClass.build(accessor.property().target())).withPrefix(result.getReturnTypeExpression().getPrefix()));
            List<J.Annotation> annotations = new ArrayList<>(result.getLeadingAnnotations());
            boolean removed = annotations.removeIf(a -> a.getId().equals(accessor.property().annotation().getId()));
            if (removed) {
                List<Comment> comments = new ArrayList<>(result.getPrefix().getComments());
                new JavaIsoVisitor<List<Comment>>() {
                    @Override public Space visitSpace(Space space, Space.Location loc, List<Comment> found) {
                        found.addAll(space.getComments());
                        return space;
                    }
                }.visit(accessor.property().annotation(), comments);
                result = result.withPrefix(result.getPrefix().withComments(comments));
            }
            result = result.withLeadingAnnotations(annotations);
            maybeRemoveImport(TEMPORAL_FQN);
            maybeRemoveImport(TEMPORAL_TYPE_FQN);
            maybeRemoveImport(TEMPORAL_TYPE_FQN + "." + plan.precision(accessor.property().annotation()));
        }
        maybeRemoveImport(accessor.property().source());
        return result;
    }

    @Override public J.MethodInvocation visitMethodInvocation(J.MethodInvocation m, ExecutionContext ctx) {
        J.MethodInvocation result = super.visitMethodInvocation(m, ctx);
        // Match the original invocation, even if an earlier file's accessor has already
        // changed. Apply edits to result, whose children have now been visited.
        TemporalConversionPlan.Accessor accessor = plan.accessor(m.getMethodType());
        if (accessor != null && accessor.property().accepted()) {
            if (!accessor.getter()) result = result.withArguments(Collections.singletonList(convertValue(result.getArguments().get(0))));
            // OpenRewrite stores the signature on both the invocation and its name.
            // Both must reference the same Method instance to keep attribution valid.
            JavaType.Method converted = convertedMethod(m.getMethodType(), accessor);
            result = result.withMethodType(converted).withName(result.getName().withType(converted));
        }
        return result;
    }

    @Override
    public J.VariableDeclarations.NamedVariable visitVariable(J.VariableDeclarations.NamedVariable v, ExecutionContext ctx) {
        J.VariableDeclarations.NamedVariable result = super.visitVariable(v, ctx);
        TemporalConversionPlan.Property c = plan.variable(v.getVariableType());
        if (c != null && c.accepted()) {
            result = result.withVariableType(v.getVariableType().withType(JavaType.ShallowClass.build(c.target())));
            if (v.getInitializer() != null) result = result.withInitializer(convertValue(result.getInitializer()));
        }
        return result;
    }

    @Override
    public J.Assignment visitAssignment(J.Assignment a, ExecutionContext ctx) {
        J.Assignment result = super.visitAssignment(a, ctx);
        TemporalConversionPlan.ValueAction action = plan.value(a.getAssignment().getId());
        if (action != null) result = result.withAssignment(convertValue(result.getAssignment()));
        return result;
    }

    @Override
    public J.Identifier visitIdentifier(J.Identifier id, ExecutionContext ctx) {
        TemporalConversionPlan.Property c = plan.variable(id.getFieldType());
        if (c != null && c.accepted()) {
            JavaType.FullyQualified type = JavaType.ShallowClass.build(c.target());
            return id.withType(type).withFieldType(id.getFieldType().withType(type));
        }
        return super.visitIdentifier(id, ctx);
    }

    private Expression convertValue(Expression value) {
        TemporalConversionPlan.ValueAction action = plan.value(value.getId());
        if (action == null || action.kind() == TemporalConversionPlan.ValueKind.PASS_THROUGH) return value;
        return action.conversion().apply(value, new Cursor(getCursor(), value), parameter);
    }
}
