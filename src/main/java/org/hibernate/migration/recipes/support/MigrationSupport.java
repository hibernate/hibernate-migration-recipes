/* SPDX-License-Identifier: Apache-2.0 */
package org.hibernate.migration.recipes.support;

import org.hibernate.migration.recipes.table.SkippedMigrations;
import org.jspecify.annotations.NonNull;
import org.openrewrite.*;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.*;

import java.util.*;

/// Shared source-preserving operations, independent of source and target API JARs.
///
/// Provides comment transfer, annotation recognition, and deduplicated reporting
/// of skipped candidates. [JavaVisitor] captures source locations before a leaf
/// recipe changes the compilation unit, keeping diagnostics tied to that input.
///
/// @author Jennifer Joby
/// @author Steve Ebersole
public final class MigrationSupport {
    private MigrationSupport() {}

    /// Combines comments in argument order while retaining the first space's whitespace.
    ///
    /// @param first the space whose whitespace and initial comments are retained
    /// @param remaining spaces supplying additional comments, without their whitespace
    /// @return a space containing all supplied comments, without deduplication
    public static Space comments(Space first, Space... remaining) {
        List<Comment> comments = new ArrayList<>(first.getComments());
        for (Space space : remaining) comments.addAll(space.getComments());
        return first.withComments(comments);
    }

    /// Checks whether source spelling explicitly identifies the requested annotation.
    ///
    /// Recognizes a fully qualified spelling or a simple spelling accompanied by an
    /// exact, non-static import. Wildcard imports and implicit package resolution are
    /// not considered. This is a syntactic check, not proof of resolved type identity.
    ///
    /// @param annotation the annotation to inspect
    /// @param fqn the fully qualified annotation name
    /// @param cu the compilation unit supplying imports
    /// @return whether the spelling and imports explicitly identify the requested name
    public static boolean explicitType(J.Annotation annotation, String fqn, J.CompilationUnit cu) {
        String spelling = annotation.getAnnotationType().printTrimmed();
        if (fqn.equals(spelling)) {
            return true;
        }
        for (J.Import imp : cu.getImports()) {
            if (!imp.isStatic() && fqn.equals(imp.getTypeName()) && annotation.getSimpleName().equals(spelling)) {
                return true;
            }
        }
        return false;
    }

    /// Converts a character offset into one-based line and column coordinates.
    ///
    /// Newlines are counted at LF characters. Columns count UTF-16 code units;
    /// tabs are counted as one unit rather than expanded into display columns.
    ///
    /// @param source the source text used to calculate the offset
    /// @param offset the UTF-16 offset, between zero and the source length inclusive
    /// @return a two-element array containing the line followed by the column
    public static int[] position(String source, int offset) {
        int line = 1, column = 1;
        for (int i = 0; i < offset; i++) {
            if (source.charAt(i) == '\n') { line++; column = 1; }
            else column++;
        }
        return new int[] {line, column};
    }

    /// Records the first skipped-candidate report for a recipe, source, and tree identity.
    ///
    /// Deduplication state is stored on the root cursor rather than the reusable
    /// recipe instance. Later reports for the same identity are suppressed even if
    /// their reason differs. Source paths are normalized to forward slashes.
    ///
    /// @param table the report table receiving the row
    /// @param recipe the leaf recipe that skipped the candidate
    /// @param cursor the active cursor whose root owns deduplication state
    /// @param ctx the execution context used to insert the row
    /// @param source the source file containing the candidate
    /// @param candidate the tree identifying the skipped candidate
    /// @param position the one-based line and column in the leaf recipe's input
    /// @param subject the annotation, method, or XML root requiring review
    /// @param reason the stable reason code
    /// @param message the explanation for manual review
    public static void report(
            SkippedMigrations table,
            Recipe recipe,
            Cursor cursor,
            ExecutionContext ctx,
            SourceFile source,
            Tree candidate,
            int[] position,
            String subject,
            String reason,
            String message) {
        // Root cursor messages are local to a recipe execution, unlike fields on a reusable recipe.
        Set<String> reported = cursor.getRoot().computeMessageIfAbsent("hibernate.skipped", k -> new HashSet<String>());
        String key = recipe.getName() + ":" + source.getId() + ":" + candidate.getId();
        if (reported.add(key)) {
            table.insertRow(ctx, new SkippedMigrations.Row(recipe.getName(), source.getSourcePath().toString().replace('\\', '/'),
                    position[0], position[1], subject, reason, message));
        }
    }

    /// Captures positions from the original leaf input without attaching source markers.
    ///
    /// Before visiting each compilation unit, prints its incoming tree and records
    /// node offsets by tree ID. Subclasses can report unchanged candidates using
    /// those positions even after earlier visits have modified surrounding code.
    /// The input may already include changes from earlier recipes in a composite.
    public abstract static class JavaVisitor extends JavaIsoVisitor<ExecutionContext> {
        private final Recipe recipe;
        private final SkippedMigrations table;
        /// The current compilation unit as received by this leaf visitor.
        protected J.CompilationUnit input;
        private SourcePositions positions;

        /// Creates a visitor that reports skipped candidates on behalf of the leaf recipe.
        ///
        /// @param recipe the recipe supplying the report identifier
        /// @param table the table receiving skipped-candidate rows
        protected JavaVisitor(Recipe recipe, SkippedMigrations table) { this.recipe = recipe; this.table = table; }

        /// Captures incoming source text and node offsets before delegating traversal.
        ///
        /// @param cu the compilation unit about to be visited
        /// @param ctx the active execution context
        /// @return the compilation unit produced by normal visitor traversal
        @Override
        public J.@NonNull CompilationUnit visitCompilationUnit(J.@NonNull CompilationUnit cu, @NonNull ExecutionContext ctx) {
            input = cu;
            positions = SourcePositions.capture(cu);
            return super.visitCompilationUnit(cu, ctx);
        }

        /// Reports a skipped candidate at its position in the captured leaf input.
        ///
        /// This method only reports the decision; the caller must leave the candidate
        /// unchanged. Candidates must retain an ID recorded from the incoming tree.
        ///
        /// @param candidate the candidate left unchanged
        /// @param ctx the active execution context
        /// @param subject the annotation or method requiring review
        /// @param reason the stable reason code
        /// @param message the explanation for manual review
        /// @throws IllegalStateException if no incoming position exists for the candidate
        protected void skip(J candidate, ExecutionContext ctx, String subject, String reason, String message) {
            report(table, recipe, getCursor(), ctx, input, candidate, positions.position(candidate.getId()), subject, reason, message);
        }

        /// Matches an annotation by resolved type and reports explicitly spelled but
        /// unresolved candidates instead of permitting a transformation.
        ///
        /// A missing or unknown type with an explicit spelling produces a
        /// `MISSING_TYPE_ATTRIBUTION` report. Other nonmatching annotations return
        /// false without a report, including unresolved wildcard-import cases.
        ///
        /// @param annotation the annotation to inspect
        /// @param fqn the fully qualified annotation type required by the recipe
        /// @param ctx the active execution context
        /// @return whether the resolved annotation type matches the requested type
        protected boolean matches(J.Annotation annotation, String fqn, ExecutionContext ctx) {
            if (TypeUtils.isOfClassType(annotation.getType(), fqn)) return true;
            if ((annotation.getType() == null || annotation.getType() instanceof JavaType.Unknown) && explicitType(annotation, fqn, input)) {
                skip(annotation, ctx, fqn, "MISSING_TYPE_ATTRIBUTION", "Resolve the source JPA API before migrating this annotation.");
            }
            return false;
        }
    }
}
