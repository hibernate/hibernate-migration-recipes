package org.hibernate.migration.recipes.orm80;

import org.hibernate.migration.recipes.support.MigrationSupport;
import org.hibernate.migration.recipes.table.SkippedMigrations;
import org.jspecify.annotations.NonNull;
import org.openrewrite.*;
import org.openrewrite.groovy.tree.G;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.*;
import org.openrewrite.kotlin.tree.K;

import java.util.*;

import static org.hibernate.migration.recipes.support.EnhancementMigrationSupport.*;

/// Migrates canonical Hibernate enhancement blocks in Groovy and Kotlin Gradle builds.
///
/// @author Steve Ebersole
public class MigrateGradleClientEnhancementOption extends Recipe {
    private final transient SkippedMigrations skipped = new SkippedMigrations(this);

    @Override public @NonNull String getDisplayName() { return "Migrate Gradle client enhancement option"; }
    @Override public @NonNull String getDescription() {
        return "Renames explicit Hibernate enhancement options while preserving client values and reporting ambiguous Gradle configuration.";
    }
    @Override public @NonNull TreeVisitor<?, ExecutionContext> getVisitor() {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override public boolean isAcceptable(@NonNull SourceFile source, @NonNull ExecutionContext ctx) {
                String name = source.getSourcePath().getFileName().toString();
                return (source instanceof G.CompilationUnit && name.equals("build.gradle"))
                        || (source instanceof K.CompilationUnit && name.equals("build.gradle.kts"));
            }
            @Override public Tree preVisit(@NonNull Tree tree, @NonNull ExecutionContext ctx) {
                if (!(tree instanceof SourceFile source)) return tree;
                stopAfterPreVisit();
                return migrate(source, ctx);
            }
        };
    }

    private J migrate(SourceFile source, ExecutionContext ctx) {
        List<Block> blocks = new ArrayList<>();
        boolean[] plugin = {false};
        new JavaIsoVisitor<Integer>() {
            @Override public J.@NonNull MethodInvocation visitMethodInvocation(J.@NonNull MethodInvocation method, @NonNull Integer p) {
                if (method.getSimpleName().equals("id") && literalArgument(method, "org.hibernate.orm")
                        && enclosingCall(getCursor(), "plugins")) plugin[0] = true;
                if (method.getSimpleName().equals("apply") && topLevelContext(getCursor())
                        && method.getArguments().stream().anyMatch(MigrateGradleClientEnhancementOption::pluginArgument))
                    plugin[0] = true;
                if (method.getSimpleName().equals("enhancement") && canonical(getCursor(), method)) {
                    for (Expression arg : method.getArguments()) {
                        if (arg instanceof J.Lambda lambda && lambda.getBody() instanceof J.Block body) {
                            boolean attributed = method.getMethodType() != null && TypeUtils.isOfClassType(
                                    method.getMethodType().getDeclaringType(), "org.hibernate.orm.tooling.gradle.HibernateOrmSpec");
                            blocks.add(inspect(body, attributed, localReceiver(getCursor())));
                        }
                    }
                }
                return super.visitMethodInvocation(method, p);
            }
        }.visit(source, 0);
        Set<UUID> candidates = new HashSet<>();
        blocks.forEach(b -> b.old.forEach(id -> candidates.add(id.getId())));
        Map<UUID, Integer> positions = offsets(source, candidates);
        Set<UUID> renames = new HashSet<>(), removals = new HashSet<>();
        for (Block block : blocks) {
            if (block.old.isEmpty()) continue;
            String reason = null, message = null;
            if (!block.local || !plugin[0] && !block.attributed) {
                reason = IDENTITY; message = "The Hibernate enhancement receiver is unresolved; establish plugin application or receiver attribution.";
            }
            else if (blocks.size() > 1 || block.oldWrites > 1 || block.newWrites > 1) {
                reason = AMBIGUOUS; message = "Multiple enhancement blocks or duplicate option writes require manual resolution.";
            }
            else if (block.unsupported || block.oldSetting == null || (!block.fresh.isEmpty() && block.newSetting == null)
                    || (block.newSetting != null && !booleanLiteral(block.oldSetting.value))) {
                reason = SYNTAX; message = "Unsupported option access or removal of a potentially side-effecting expression requires manual migration.";
            }
            if (reason != null) {
                for (J.Identifier old : block.old) report(this, skipped, source, positions, old.getId(), reason, message, ctx);
            }
            else if (block.newSetting == null) renames.add(block.oldSetting.identifier.getId());
            else removals.add(block.oldSetting.statement.getId());
        }
        if (renames.isEmpty() && removals.isEmpty()) return (J) source;
        return new JavaIsoVisitor<Integer>() {
            @Override public J.@NonNull Identifier visitIdentifier(J.@NonNull Identifier identifier, @NonNull Integer p) {
                J.Identifier result = super.visitIdentifier(identifier, p);
                return renames.contains(identifier.getId()) ? result.withSimpleName(NEW) : result;
            }
            @Override public J.@NonNull Block visitBlock(J.@NonNull Block block, @NonNull Integer p) {
                J.Block result = super.visitBlock(block, p);
                List<JRightPadded<Statement>> kept = new ArrayList<>();
                Space pending = Space.EMPTY;
                for (JRightPadded<Statement> padded : result.getPadding().getStatements()) {
                    Statement statement = padded.getElement();
                    if (removals.contains(statement.getId())) pending = MigrationSupport.comments(pending, statement.getPrefix());
                    else {
                        if (!pending.getComments().isEmpty()) {
                            statement = statement.withPrefix(MigrateGradleClientEnhancementOption.prependComments(statement.getPrefix(), pending));
                            pending = Space.EMPTY;
                        }
                        kept.add(padded.withElement(statement));
                    }
                }
                result = result.getPadding().withStatements(kept);
                return pending.getComments().isEmpty() ? result : result.withEnd(prependComments(result.getEnd(), pending));
            }
        }.visitNonNull(source, 0);
    }

    private static Space prependComments(Space destination, Space preceding) {
        List<Comment> comments = new ArrayList<>(preceding.getComments());
        comments.addAll(destination.getComments());
        return destination.withComments(comments);
    }

    private record Setting(Statement statement, J.Identifier identifier, Expression value) {}
    private record Block(List<J.Identifier> old, List<J.Identifier> fresh, Setting oldSetting, Setting newSetting,
                         int oldWrites, int newWrites, boolean unsupported, boolean attributed, boolean local) {}

    private static Block inspect(J.Block body, boolean attributed, boolean local) {
        List<J.Identifier> old = new ArrayList<>(), fresh = new ArrayList<>();
        int[] writes = {0, 0};
        new JavaIsoVisitor<Integer>() {
            @Override public J.@NonNull Identifier visitIdentifier(J.@NonNull Identifier id, @NonNull Integer p) {
                if (OLD.equals(id.getSimpleName()) || NEW.equals(id.getSimpleName())) {
                    Cursor parent = getCursor().getParentTreeCursor();
                    if (!(parent.getValue() instanceof J.FieldAccess access) || access.getName().getId().equals(id.getId())
                            && self(access.getTarget())) {
                        (OLD.equals(id.getSimpleName()) ? old : fresh).add(id);
                    }
                }
                return super.visitIdentifier(id, p);
            }
            @Override public J.@NonNull Assignment visitAssignment(J.@NonNull Assignment assignment, @NonNull Integer p) {
                count(option(assignment.getVariable()));
                return super.visitAssignment(assignment, p);
            }
            @Override public J.@NonNull MethodInvocation visitMethodInvocation(J.@NonNull MethodInvocation method, @NonNull Integer p) {
                if (Set.of("set", "convention", "value").contains(method.getSimpleName())) count(option(method.getSelect()));
                return super.visitMethodInvocation(method, p);
            }
            private void count(J.Identifier id) {
                if (id != null) writes[OLD.equals(id.getSimpleName()) ? 0 : 1]++;
            }
        }.visit(body, 0);
        Setting oldSetting = null, newSetting = null;
        Set<UUID> supported = new HashSet<>();
        for (Statement statement : body.getStatements()) {
            J tree = statement;
            if (tree instanceof J.Return returned && (returned.getMarkers().findFirst(org.openrewrite.groovy.marker.ImplicitReturn.class).isPresent()
                    || returned.getMarkers().findFirst(org.openrewrite.java.marker.ImplicitReturn.class).isPresent())) tree = returned.getExpression();
            if (tree instanceof K.ExpressionStatement wrapper) tree = wrapper.getExpression();
            if (tree instanceof G.ExpressionStatement wrapper) tree = wrapper.getExpression();
            J.Identifier id = null;
            Expression value = null;
            if (tree instanceof J.Assignment assignment) { id = option(assignment.getVariable()); value = assignment.getAssignment(); }
            else if (tree instanceof J.MethodInvocation method && method.getSimpleName().equals("set") && method.getArguments().size() == 1) {
                id = option(method.getSelect()); value = method.getArguments().get(0);
            }
            if (id != null) {
                supported.add(id.getId());
                Setting setting = new Setting(statement, id, value);
                if (OLD.equals(id.getSimpleName())) oldSetting = setting; else newSetting = setting;
            }
        }
        boolean unsupported = java.util.stream.Stream.concat(old.stream(), fresh.stream()).anyMatch(id -> !supported.contains(id.getId()));
        return new Block(old, fresh, oldSetting, newSetting, writes[0], writes[1], unsupported, attributed, local);
    }

    private static J.Identifier option(Expression expression) {
        J.Identifier id = expression instanceof J.Identifier identifier ? identifier
                : expression instanceof J.FieldAccess access && self(access.getTarget()) ? access.getName() : null;
        return id != null && (OLD.equals(id.getSimpleName()) || NEW.equals(id.getSimpleName())) ? id : null;
    }

    private static boolean self(Expression expression) {
        return expression instanceof J.Identifier id && id.getSimpleName().equals("this")
                || expression instanceof K.This receiver && receiver.getLabel() == null;
    }

    private static boolean booleanLiteral(Expression expression) {
        return expression instanceof J.Literal literal && literal.getValue() instanceof Boolean;
    }

    private static boolean canonical(Cursor cursor, J.MethodInvocation enhancement) {
        if (enhancement.getSelect() != null && !self(enhancement.getSelect())) return false;
        // Only the immediate enclosing call may supply the canonical hibernate receiver.
        for (Cursor c = cursor.getParent(); c != null; c = c.getParent()) {
            if (c.getValue() instanceof J.MethodInvocation call) return call.getSimpleName().equals("hibernate")
                    && (call.getSelect() == null || call.getSelect() instanceof J.Identifier id && Set.of("project", "this").contains(id.getSimpleName()));
        }
        return false;
    }

    private static boolean enclosingCall(Cursor cursor, String name) {
        for (Cursor c = cursor.getParent(); c != null; c = c.getParent()) {
            if (c.getValue() instanceof J.MethodInvocation method) {
                if (method.getSimpleName().equals(name)) return topLevelContext(c);
                if (!Set.of("version", "apply").contains(method.getSimpleName())) return false;
                if (method.getSimpleName().equals("apply") && method.getArguments().stream()
                        .anyMatch(arg -> arg instanceof J.Literal literal && Boolean.FALSE.equals(literal.getValue()))) return false;
            }
        }
        return false;
    }

    private static boolean topLevelContext(Cursor cursor) {
        for (Cursor c = cursor.getParent(); c != null; c = c.getParent())
            if (c.getValue() instanceof J.Lambda || c.getValue() instanceof J.If || c.getValue() instanceof J.ClassDeclaration)
                return false;
        return true;
    }

    private static boolean localReceiver(Cursor cursor) {
        for (Cursor c = cursor.getParent(); c != null; c = c.getParent())
            if (c.getValue() instanceof J.MethodInvocation method && method.getSimpleName().equals("hibernate"))
                return topLevelContext(c);
        return false;
    }

    private static boolean literalArgument(J.MethodInvocation method, String value) {
        return method.getArguments().stream().anyMatch(arg -> arg instanceof J.Literal literal && value.equals(literal.getValue()));
    }

    private static boolean pluginArgument(Expression expression) {
        if (expression instanceof J.Assignment assignment && assignment.getVariable() instanceof J.Identifier id
                && id.getSimpleName().equals("plugin")) return assignment.getAssignment() instanceof J.Literal literal
                && "org.hibernate.orm".equals(literal.getValue());
        if (expression instanceof G.MapEntry entry) return entry.getKey() instanceof J.Literal key && "plugin".equals(key.getValue())
                && entry.getValue() instanceof J.Literal value && "org.hibernate.orm".equals(value.getValue());
        return false;
    }
}
