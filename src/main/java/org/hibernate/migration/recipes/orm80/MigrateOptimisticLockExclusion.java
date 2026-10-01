package org.hibernate.migration.recipes.orm80;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.hibernate.migration.recipes.support.MigrationSupport;
import org.hibernate.migration.recipes.table.SkippedMigrations;
import org.jspecify.annotations.NonNull;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.*;

/// Migrates explicit optimistic-lock exclusions without changing uncertain inclusion defaults.
///
/// @author Steve Ebersole
public class MigrateOptimisticLockExclusion extends Recipe {
	private static final String OLD = "org.hibernate.annotations.OptimisticLock";
	private static final String NEW = "jakarta.persistence.ExcludedFromVersioning";
	private static final Set<String> MAPPINGS = Set.of("Basic", "ElementCollection", "OneToMany", "ManyToMany",
			"ManyToOne", "OneToOne", "Embedded", "EmbeddedId");
	private static final Set<String> UNCERTAIN = Set.of("Access", "AttributeOverride", "AttributeOverrides",
			"AssociationOverride", "AssociationOverrides");
	private final transient SkippedMigrations skipped = new SkippedMigrations(this);

	@Override
	public @NonNull String getDisplayName() {
		return "Migrate optimistic-lock exclusion";
	}

	@Override
	public @NonNull String getDescription() {
		return "Replaces OptimisticLock exclusions with Jakarta Persistence ExcludedFromVersioning, "
				+ "removing explicit inclusion only where equivalent to the mapping default.";
	}

	@Override
	public @NonNull TreeVisitor<?, ExecutionContext> getVisitor() {
		return new MigrationSupport.JavaVisitor(this, skipped) {
			private boolean nameConflict;

			@Override
			public J.@NonNull CompilationUnit visitCompilationUnit(J.@NonNull CompilationUnit cu, @NonNull ExecutionContext ctx) {
				boolean[] conflict = {false};
				new JavaIsoVisitor<boolean[]>() {
					@Override
					public J.@NonNull Identifier visitIdentifier(J.@NonNull Identifier id, boolean @NonNull [] found) {
						if ("ExcludedFromVersioning".equals(id.getSimpleName()) && !TypeUtils.isOfClassType(id.getType(), NEW)) {
							found[0] = true;
						}
						return super.visitIdentifier(id, found);
					}
				}.visit(cu, conflict);
				nameConflict = conflict[0];
				return super.visitCompilationUnit(cu, ctx);
			}

			@Override
			public J.@NonNull VariableDeclarations visitVariableDeclarations(J.@NonNull VariableDeclarations v, @NonNull ExecutionContext ctx) {
				J.VariableDeclarations result = super.visitVariableDeclarations(v, ctx);
				if (!(getCursor().getParentTreeCursor().getValue() instanceof J.Block)
						|| !(getCursor().getParentTreeCursor().getParentTreeCursor().getValue() instanceof J.ClassDeclaration
								|| getCursor().getParentTreeCursor().getParentTreeCursor().getValue() instanceof J.NewClass)) return result;
				var edit = migrate(v.getLeadingAnnotations(), v, ctx);
				return result.withLeadingAnnotations(edit.annotations()).withPrefix(edit.comments().getComments().isEmpty() ? result.getPrefix() : MigrationSupport.comments(result.getPrefix(), edit.comments()));
			}

			@Override
			public J.@NonNull MethodDeclaration visitMethodDeclaration(J.@NonNull MethodDeclaration m, @NonNull ExecutionContext ctx) {
				J.MethodDeclaration result = super.visitMethodDeclaration(m, ctx);
				var edit = migrate(m.getLeadingAnnotations(), m, ctx);
				return result.withLeadingAnnotations(edit.annotations()).withPrefix(edit.comments().getComments().isEmpty() ? result.getPrefix() : MigrationSupport.comments(result.getPrefix(), edit.comments()));
			}

			private Edit migrate(List<J.Annotation> annotations, J member, ExecutionContext ctx) {
				List<J.Annotation> old = annotations.stream().filter(a -> identified(a, OLD)).toList();
				if (old.isEmpty()) return new Edit(annotations, Space.EMPTY);
				List<J.Annotation> replacements = annotations.stream().filter(a -> identified(a, NEW)).toList();
				boolean duplicate = old.size() > 1 || replacements.size() > 1;
				List<J.Annotation> result = new ArrayList<>();
				Space removed = Space.EMPTY;
				for (J.Annotation a : annotations) {
					if (!old.contains(a)) { result.add(a); continue; }
					Boolean excluded = literal(a);
					if (duplicate || !replacements.isEmpty() && Boolean.FALSE.equals(excluded)) {
						skip(a, ctx, OLD, "OPTIMISTIC_LOCK_CONFLICT", "Duplicate or contradictory exclusion annotations require manual resolution.");
						result.add(a); continue;
					}
					if (!TypeUtils.isOfClassType(a.getType(), OLD)) {
						skip(a, ctx, OLD, "MISSING_TYPE_ATTRIBUTION", "Resolve the source Hibernate API before migrating this annotation.");
						result.add(a); continue;
					}
					if (!shape(a)) {
						skip(a, ctx, OLD, "UNSUPPORTED_ANNOTATION_SHAPE", "Expected one explicit excluded member.");
						result.add(a); continue;
					}
					if (excluded == null) {
						skip(a, ctx, OLD, "OPTIMISTIC_LOCK_VALUE_UNSUPPORTED", "Expected a literal boolean excluded value.");
						result.add(a); continue;
					}
					if (!replacements.isEmpty() && !TypeUtils.isOfClassType(replacements.get(0).getType(), NEW)
							|| !excluded && !defaultIncluded(annotations, member)) {
						skip(a, ctx, OLD, "OPTIMISTIC_LOCK_DEFAULT_UNVERIFIED", "Default inclusion or replacement identity is not established; retain the explicit setting.");
						result.add(a); continue;
					}
					Space comments = annotationComments(a);
					if (excluded && replacements.isEmpty()) {
						boolean qualified = nameConflict || a.getAnnotationType() instanceof J.FieldAccess;
						NameTree type = qualified
								? (NameTree) TypeTree.build(NEW).withType(JavaType.ShallowClass.build(NEW))
								: ((J.Identifier) a.getAnnotationType()).withSimpleName("ExcludedFromVersioning")
										.withType(JavaType.ShallowClass.build(NEW));
						result.add(a.withAnnotationType(type.withPrefix(Space.EMPTY)).withArguments(null).withPrefix(comments));
						if (!qualified) maybeAddImport(NEW);
					}
					else removed = MigrationSupport.comments(removed, comments);
					maybeRemoveImport(OLD);
				}
				boolean unchanged = result.size() == annotations.size();
				for (int i = 0; unchanged && i < result.size(); i++) unchanged = result.get(i) == annotations.get(i);
				return new Edit(unchanged ? annotations : result, removed);
			}

			private boolean identified(J.Annotation a, String type) {
				return TypeUtils.isOfClassType(a.getType(), type)
						|| (a.getType() == null || a.getType() instanceof JavaType.Unknown) && MigrationSupport.explicitType(a, type, input);
			}

			private boolean defaultIncluded(List<J.Annotation> annotations, J member) {
				J.ClassDeclaration owner = getCursor().firstEnclosing(J.ClassDeclaration.class);
				if (owner == null || owner.getExtends() != null || uncertain(owner.getLeadingAnnotations()) || uncertain(annotations)) return false;
				String property = property(member);
				if (property == null) return false;
				for (Statement sibling : owner.getBody().getStatements()) {
					if (!sibling.getId().equals(member.getId()) && property.equals(property(sibling))) {
						List<J.Annotation> other = sibling instanceof J.VariableDeclarations v ? v.getLeadingAnnotations()
								: ((J.MethodDeclaration) sibling).getLeadingAnnotations();
						if (other.stream().anyMatch(a -> a.getType() == null || a.getType() instanceof JavaType.Unknown
								|| a.getType().toString().startsWith("jakarta.persistence.")
								|| a.getType().toString().startsWith("org.hibernate.annotations."))) return false;
					}
				}
				List<J.Annotation> mapping = annotations.stream().filter(a -> MAPPINGS.stream()
						.anyMatch(n -> TypeUtils.isOfClassType(a.getType(), "jakarta.persistence." + n))).toList();
				if (mapping.size() != 1) return false;
				J.Annotation a = mapping.get(0);
				if (TypeUtils.isOfClassType(a.getType(), "jakarta.persistence.Basic")
						|| TypeUtils.isOfClassType(a.getType(), "jakarta.persistence.ElementCollection")) return true;
				if (!TypeUtils.isOfClassType(a.getType(), "jakarta.persistence.OneToMany")
						&& !TypeUtils.isOfClassType(a.getType(), "jakarta.persistence.ManyToMany")) return false;
				if (a.getArguments() != null) for (Expression arg : a.getArguments()) {
					if (arg instanceof J.Assignment assignment && assignment.getVariable() instanceof J.Identifier id
							&& "mappedBy".equals(id.getSimpleName())) {
						return assignment.getAssignment() instanceof J.Literal l && "".equals(l.getValue());
					}
				}
				return true;
			}

			private boolean uncertain(List<J.Annotation> annotations) {
				return annotations.stream().anyMatch(a -> {
					if (TypeUtils.isOfClassType(a.getType(), OLD)) return false;
					if (!(a.getType() instanceof JavaType.FullyQualified type)) return true;
					return UNCERTAIN.contains(type.getClassName())
							|| !(type.getFullyQualifiedName().startsWith("jakarta.persistence.")
									|| type.getFullyQualifiedName().startsWith("java.lang."));
				});
			}
		};
	}

	private static String property(J member) {
		if (member instanceof J.VariableDeclarations v && v.getVariables().size() == 1) return v.getVariables().get(0).getSimpleName();
		if (member instanceof J.MethodDeclaration m) {
			String name = m.getSimpleName();
			int prefix = name.startsWith("get") ? 3 : name.startsWith("is") ? 2 : 0;
			if (prefix > 0 && name.length() > prefix && (m.getParameters().isEmpty() || m.getParameters().get(0) instanceof J.Empty)) {
				String suffix = name.substring(prefix);
				return suffix.length() > 1 && Character.isUpperCase(suffix.charAt(1)) ? suffix
						: Character.toLowerCase(suffix.charAt(0)) + suffix.substring(1);
			}
		}
		return null;
	}

	private static boolean shape(J.Annotation a) {
		return a.getArguments() != null && a.getArguments().size() == 1
				&& a.getArguments().get(0) instanceof J.Assignment assignment
				&& assignment.getVariable() instanceof J.Identifier id && "excluded".equals(id.getSimpleName());
	}

	private static Boolean literal(J.Annotation a) {
		return shape(a) && ((J.Assignment) a.getArguments().get(0)).getAssignment() instanceof J.Literal l
				&& l.getValue() instanceof Boolean b ? b : null;
	}

	private static Space annotationComments(J.Annotation a) {
		List<org.openrewrite.java.tree.Comment> comments = new ArrayList<>();
		new JavaIsoVisitor<List<org.openrewrite.java.tree.Comment>>() {
			@Override
			public @NonNull Space visitSpace(@NonNull Space space, Space.@NonNull Location loc, @NonNull List<org.openrewrite.java.tree.Comment> found) {
				found.addAll(space.getComments());
				return space;
			}
		}.visit(a, comments);
		return a.getPrefix().withComments(comments);
	}

	private record Edit(List<J.Annotation> annotations, Space comments) {}
}
