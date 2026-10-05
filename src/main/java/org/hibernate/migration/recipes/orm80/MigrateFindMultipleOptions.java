package org.hibernate.migration.recipes.orm80;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hibernate.migration.recipes.support.ChangeTypeSupport;
import org.jspecify.annotations.NonNull;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeTree;
import org.openrewrite.java.tree.TypeUtils;

/// Relocates multi-ID loading options to the nested types in `FindMultipleOption`.
///
/// @author Steve Ebersole
public class MigrateFindMultipleOptions extends Recipe {
	private static final String OWNER = "org.hibernate.FindMultipleOption";
	private static final List<String> OPTIONS = List.of("OrderingMode", "SessionCheckMode", "RemovalsMode", "BatchSize");

	@Override
	public @NonNull String getDisplayName() {
		return "Migrate multi-ID loading options";
	}

	@Override
	public @NonNull String getDescription() {
		return "Replaces Hibernate's top-level multi-ID loading options with nested FindMultipleOption types, "
				+ "preserving explicit option values and loading calls.";
	}

	@Override
	public @NonNull TreeVisitor<?, ExecutionContext> getVisitor() {
		return new JavaIsoVisitor<>() {
			@Override
			public J.@NonNull CompilationUnit visitCompilationUnit(
					J.@NonNull CompilationUnit cu, @NonNull ExecutionContext ctx) {
				if (OPTIONS.stream().noneMatch(option -> new UsesType<ExecutionContext>("org.hibernate." + option, false)
						.visitNonNull(cu, ctx) != cu)) {
					return cu;
				}
				// Preserve existing nested references even if ChangeType shortens other references globally.
				Map<UUID, J> existingReferences = new HashMap<>();
				J.CompilationUnit migrated = (J.CompilationUnit) new JavaVisitor<ExecutionContext>() {
					private boolean isExistingReference(Expression expression) {
						return getCursor().firstEnclosing(J.Import.class) == null && OPTIONS.stream()
								.anyMatch(option -> TypeUtils.isOfClassType(expression.getType(), OWNER + "$" + option));
					}

					@Override
					public @NonNull J visitFieldAccess(
							J.@NonNull FieldAccess access,
							@NonNull ExecutionContext context) {
						if (access.getName().getFieldType() == null && isExistingReference(access)) {
							existingReferences.put(access.getId(), access);
							return access.getName().withId(access.getId()).withType(null)
									.withSimpleName("__existingHibernateOptionReference").withPrefix(access.getPrefix());
						}
						return super.visitFieldAccess(access, context);
					}

					@Override
					public @NonNull J visitIdentifier(
							J.@NonNull Identifier identifier,
							@NonNull ExecutionContext context) {
						if (identifier.getFieldType() == null && isExistingReference(identifier)) {
							existingReferences.put(identifier.getId(), identifier);
							return identifier.withType(null).withSimpleName("__existingHibernateOptionReference");
						}
						return super.visitIdentifier(identifier, context);
					}
				}.visitNonNull(cu, ctx);
				for (String option : OPTIONS) {
					String oldType = "org.hibernate." + option;
					String newType = OWNER + "$" + option;
					if (new UsesType<ExecutionContext>(oldType, false).visitNonNull(migrated, ctx) == migrated) {
						continue;
					}
					boolean conflict = ChangeTypeSupport.hasNameConflict(cu, option, oldType, newType);

					J.CompilationUnit before = migrated;
					// ChangeType also recognizes fully qualified names by spelling. Shield references
					// whose attribution does not identify the Hibernate type, then restore them.
					Map<UUID, J.FieldAccess> protectedReferences = new HashMap<>();
					migrated = (J.CompilationUnit) new JavaVisitor<ExecutionContext>() {
						@Override
						public @NonNull J visitFieldAccess(
								J.@NonNull FieldAccess access,
								@NonNull ExecutionContext context) {
							if (access.isFullyQualifiedClassReference(oldType)
									&& !TypeUtils.isOfClassType(access.getType(), oldType)) {
								protectedReferences.put(access.getId(), access);
								return access.getName().withId(access.getId()).withPrefix(access.getPrefix())
										.withSimpleName("__protectedHibernateOptionReference").withType(null).withFieldType(null);
							}
							return super.visitFieldAccess(access, context);
						}
					}.visitNonNull(migrated, ctx);
					migrated = ChangeTypeSupport.changeTypePreservingWildcards(
							migrated, before, oldType, newType, ctx, getCursor().getParentOrThrow());
					migrated = (J.CompilationUnit) new JavaVisitor<ExecutionContext>() {
						@Override
						public @NonNull J visitIdentifier(
								J.@NonNull Identifier identifier,
								@NonNull ExecutionContext context) {
							J.FieldAccess original = protectedReferences.get(identifier.getId());
							return original == null ? super.visitIdentifier(identifier, context) : original;
						}
					}.visitNonNull(migrated, ctx);

					// ChangeType introduces owner-qualified names. Import the nested type directly.
					migrated = (J.CompilationUnit) new JavaVisitor<ExecutionContext>() {
						@Override
						public @NonNull J visitFieldAccess(
								J.@NonNull FieldAccess access,
								@NonNull ExecutionContext context) {
							if (getCursor().firstEnclosing(J.Import.class) == null
									&& option.equals(access.getSimpleName())
									&& access.getName().getFieldType() == null
									&& TypeUtils.isOfClassType(access.getType(), newType)) {
								maybeAddImport(newType);
								maybeRemoveImport(OWNER);
								return access.getName().withPrefix(access.getPrefix()).withType(access.getType());
							}
							return super.visitFieldAccess(access, context);
						}
					}.visitNonNull(migrated, ctx);

					if (conflict) {
						// Inner class types need manual FieldAccess construction to produce dot-separated
						// source spelling; FullyQualifyTypeReference does not convert $ to dot notation.
						migrated = (J.CompilationUnit) new JavaVisitor<ExecutionContext>() {
							@Override
							public @NonNull J visitIdentifier(
									J.@NonNull Identifier identifier,
									@NonNull ExecutionContext context) {
								if (identifier.getFieldType() == null
										&& TypeUtils.isOfClassType(identifier.getType(), newType)
										&& getCursor().firstEnclosing(J.Import.class) == null) {
									J.FieldAccess qualified = TypeTree.build(newType.replace('$', '.'));
									return qualified.withTarget(qualified.getTarget().withType(JavaType.ShallowClass.build(OWNER)))
											.withName(qualified.getName().withType(identifier.getType()))
											.withPrefix(identifier.getPrefix());
								}
								return super.visitIdentifier(identifier, context);
							}
						}.visitNonNull(migrated, ctx);
						migrated = migrated.withImports(migrated.getImports().stream()
								.filter(imp -> imp.isStatic() || !TypeUtils.fullyQualifiedNamesAreEqual(newType, imp.getTypeName()))
								.toList());
					}
				}
				var imports = new ArrayList<>(migrated.getImports());
				for (J.Import original : cu.getImports()) {
					if ((OWNER.equals(original.getTypeName()) || OPTIONS.stream().anyMatch(option ->
							TypeUtils.fullyQualifiedNamesAreEqual(OWNER + "$" + option, original.getTypeName())))
							&& imports.stream().noneMatch(imp -> imp.isStatic() == original.isStatic()
									&& TypeUtils.fullyQualifiedNamesAreEqual(imp.getTypeName(), original.getTypeName())
									&& imp.getQualid().getSimpleName().equals(original.getQualid().getSimpleName()))) {
						imports.add(original);
					}
				}
				migrated = migrated.withImports(imports);
				return (J.CompilationUnit) new JavaVisitor<ExecutionContext>() {
					@Override
					public @NonNull J visitIdentifier(
							J.@NonNull Identifier identifier,
							@NonNull ExecutionContext context) {
						J original = existingReferences.get(identifier.getId());
						return original == null ? super.visitIdentifier(identifier, context) : original;
					}
				}.visitNonNull(migrated, ctx);
			}
		};
	}
}
