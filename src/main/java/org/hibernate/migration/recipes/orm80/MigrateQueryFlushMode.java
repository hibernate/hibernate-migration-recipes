package org.hibernate.migration.recipes.orm80;

import java.util.ArrayList;

import org.jspecify.annotations.NonNull;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.ChangeType;
import org.openrewrite.java.FullyQualifyTypeReference;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeTree;
import org.openrewrite.java.tree.TypeUtils;

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
                boolean[] conflict = {false};
                new JavaIsoVisitor<boolean[]>() {
                    @Override
                    public J.@NonNull Identifier visitIdentifier(
							J.@NonNull Identifier identifier, boolean @NonNull [] found) {
                        if ("QueryFlushMode".equals(identifier.getSimpleName())
                                && !TypeUtils.isOfClassType(identifier.getType(), OLD_TYPE)
                                && !TypeUtils.isOfClassType(identifier.getType(), NEW_TYPE)) {
                            found[0] = true;
                        }
                        return super.visitIdentifier(identifier, found);
                    }
                }.visit(cu, conflict);

                J.CompilationUnit migrated = (J.CompilationUnit) new ChangeType(OLD_TYPE, NEW_TYPE, true)
                        .getVisitor().visitNonNull(cu, ctx, getCursor().getParentOrThrow());

                // ChangeType can drop a wildcard when an explicit static import is also present.
                for (J.Import original : cu.getImports()) {
                    if (original.isStatic() && OLD_TYPE.equals(original.getTypeName())
                            && "*".equals(original.getQualid().getSimpleName())
                            && migrated.getImports().stream().noneMatch(imp -> imp.isStatic()
                                    && NEW_TYPE.equals(imp.getTypeName())
                                    && "*".equals(imp.getQualid().getSimpleName()))) {
                        Expression target = TypeTree.build(NEW_TYPE).withType(JavaType.ShallowClass.build(NEW_TYPE));
                        var imports = new ArrayList<>(migrated.getImports());
                        imports.add(original.withQualid(original.getQualid().withTarget(target)));
                        migrated = migrated.withImports(imports);
                    }
                }
                if (conflict[0]) {
                    migrated = (J.CompilationUnit) new FullyQualifyTypeReference<ExecutionContext>(
                            JavaType.ShallowClass.build(NEW_TYPE)).visitNonNull(migrated, ctx);
                    // Fully qualified references need no ordinary import, which can itself clash.
                    migrated = migrated.withImports(migrated.getImports().stream()
                            .filter(imp -> imp.isStatic() || !NEW_TYPE.equals(imp.getTypeName())).toList());
                }
                return migrated;
            }
        });
    }
}
