package org.hibernate.migration.recipes.orm80;

import org.hibernate.migration.recipes.support.ChangeTypeSupport;
import org.jspecify.annotations.NonNull;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.J;

/// Relocates the query flush-mode enum while preserving wildcard imports and name resolution.
///
/// @author Steve Ebersole
public class MigrateQueryFlushMode extends Recipe {
    private static final String OLD_TYPE = "org.hibernate.query.QueryFlushMode";
    private static final String NEW_TYPE = "jakarta.persistence.QueryFlushMode";

    @Override
    public @NonNull String getDisplayName() {
		//noinspection DialogTitleCapitalization
		return "Migrate QueryFlushMode to Jakarta Persistence";
    }

    @Override
    public @NonNull String getDescription() {
        return "Replaces org.hibernate.query.QueryFlushMode with jakarta.persistence.QueryFlushMode, "
                + "preserving enum constants and query method calls.";
    }

    @Override
    public @NonNull TreeVisitor<?, ExecutionContext> getVisitor() {
		return Preconditions.check(new UsesType<>(OLD_TYPE, false), new JavaIsoVisitor<>() {
            @Override
            public J.@NonNull CompilationUnit visitCompilationUnit(
					J.@NonNull CompilationUnit cu,
					@NonNull ExecutionContext ctx) {
                boolean conflict = ChangeTypeSupport.hasNameConflict(cu, "QueryFlushMode", OLD_TYPE, NEW_TYPE);
                J.CompilationUnit migrated = ChangeTypeSupport.changeTypePreservingWildcards(
                        cu, cu, OLD_TYPE, NEW_TYPE, ctx, getCursor().getParentOrThrow());
                if (conflict) {
                    migrated = ChangeTypeSupport.fullyQualifyOnConflict(migrated, NEW_TYPE, ctx);
                }
                return migrated;
            }
        });
    }
}
