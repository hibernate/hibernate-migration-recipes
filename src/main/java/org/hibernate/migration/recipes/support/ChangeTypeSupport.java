package org.hibernate.migration.recipes.support;

import java.util.ArrayList;

import org.jspecify.annotations.NonNull;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.java.ChangeType;
import org.openrewrite.java.FullyQualifyTypeReference;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeTree;
import org.openrewrite.java.tree.TypeUtils;

/// Shared operations for recipes that wrap {@link ChangeType} with edge-case handling.
///
/// {@link ChangeType} can drop wildcard static imports when an explicit static import
/// is also present, and does not fully qualify references when the simple name collides
/// with another type in the same compilation unit. These utilities address both issues.
///
/// @author Steve Ebersole
public final class ChangeTypeSupport {
	private ChangeTypeSupport() {}

	/// Detects whether the simple name of a migrated type collides with an unrelated identifier.
	///
	/// @param cu the compilation unit to scan
	/// @param simpleName the simple name to check for collisions
	/// @param oldType the fully qualified name of the type being replaced
	/// @param newType the fully qualified name of the replacement type
	/// @return whether a name collision exists
	public static boolean hasNameConflict(J.CompilationUnit cu, String simpleName, String oldType, String newType) {
		boolean[] conflict = {false};
		new JavaIsoVisitor<boolean[]>() {
			@Override
			public J.@NonNull Identifier visitIdentifier(J.@NonNull Identifier identifier, boolean @NonNull [] found) {
				if (simpleName.equals(identifier.getSimpleName())
						&& !TypeUtils.isOfClassType(identifier.getType(), oldType)
						&& !TypeUtils.isOfClassType(identifier.getType(), newType)) {
					found[0] = true;
				}
				return super.visitIdentifier(identifier, found);
			}
		}.visit(cu, conflict);
		return conflict[0];
	}

	/// Applies {@link ChangeType} and restores any wildcard static import that was dropped.
	///
	/// @param cu the compilation unit to transform
	/// @param original the compilation unit before transformation, used to detect dropped imports
	/// @param oldType the fully qualified name of the type being replaced
	/// @param newType the fully qualified name of the replacement type
	/// @param ctx the active execution context
	/// @param parentCursor the parent cursor for the visitor
	/// @return the transformed compilation unit with wildcard imports preserved
	public static J.CompilationUnit changeTypePreservingWildcards(
			J.CompilationUnit cu, J.CompilationUnit original,
			String oldType, String newType,
			ExecutionContext ctx, Cursor parentCursor) {
		J.CompilationUnit migrated = (J.CompilationUnit) new ChangeType(oldType, newType, true)
				.getVisitor().visitNonNull(cu, ctx, parentCursor);
		return preserveWildcardStaticImport(migrated, original, oldType, newType);
	}

	/// Restores a wildcard static import that {@link ChangeType} may have dropped.
	///
	/// @param migrated the compilation unit after {@link ChangeType}
	/// @param original the compilation unit before transformation
	/// @param oldType the fully qualified name of the type being replaced
	/// @param newType the fully qualified name of the replacement type
	/// @return the compilation unit with the wildcard import restored if it was dropped
	public static J.CompilationUnit preserveWildcardStaticImport(
			J.CompilationUnit migrated, J.CompilationUnit original,
			String oldType, String newType) {
		for (J.Import imp : original.getImports()) {
			if (imp.isStatic() && oldType.equals(imp.getTypeName())
					&& "*".equals(imp.getQualid().getSimpleName())
					&& migrated.getImports().stream().noneMatch(i -> i.isStatic()
							&& TypeUtils.fullyQualifiedNamesAreEqual(newType, i.getTypeName())
							&& "*".equals(i.getQualid().getSimpleName()))) {
				Expression target = TypeTree.build(newType).withType(JavaType.ShallowClass.build(newType));
				var imports = new ArrayList<>(migrated.getImports());
				imports.add(imp.withQualid(imp.getQualid().withTarget(target)));
				migrated = migrated.withImports(imports);
			}
		}
		return migrated;
	}

	/// Fully qualifies all references to the given type and removes its ordinary import.
	///
	/// Used when a name collision is detected: fully qualifying all references avoids
	/// ambiguity, and the ordinary import is no longer needed (and could itself clash).
	///
	/// @param cu the compilation unit to transform
	/// @param newType the fully qualified name of the type to fully qualify
	/// @param ctx the active execution context
	/// @return the compilation unit with fully qualified references and no ordinary import
	public static J.CompilationUnit fullyQualifyOnConflict(
			J.CompilationUnit cu, String newType, ExecutionContext ctx) {
		cu = (J.CompilationUnit) new FullyQualifyTypeReference<ExecutionContext>(
				JavaType.ShallowClass.build(newType)).visitNonNull(cu, ctx);
		return cu.withImports(cu.getImports().stream()
				.filter(imp -> imp.isStatic() || !TypeUtils.fullyQualifiedNamesAreEqual(newType, imp.getTypeName()))
				.toList());
	}
}
