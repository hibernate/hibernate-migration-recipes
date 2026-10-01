package org.hibernate.migration.recipes.orm80;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.hibernate.migration.recipes.support.MigrationSupport;
import org.hibernate.migration.recipes.table.SkippedMigrations;
import org.jspecify.annotations.NonNull;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.Comment;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JLeftPadded;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.TypeTree;
import org.openrewrite.java.tree.TypeUtils;

/// Migrates query-hint constants without changing their string values.
///
/// @author Steve Ebersole
public class MigrateQueryHints extends Recipe {
	private static final String OLD = "org.hibernate.jpa.QueryHints";
	private static final String PACKAGE = "org.hibernate.jpa.";
	static final Map<String, Target> MAPPINGS = mappings();
	private final transient SkippedMigrations skipped = new SkippedMigrations(this);

	/// The replacement owner and field, independent of either ORM runtime.
	///
	/// @author Steve Ebersole
	record Target(String owner, String field) {}

	private static Map<String, Target> mappings() {
		Map<String, Target> result = new LinkedHashMap<>();
		for (String name : new String[] {"HINT_COMMENT", "HINT_FETCH_SIZE", "HINT_CACHEABLE", "HINT_CACHE_REGION",
				"HINT_CACHE_MODE", "HINT_FLUSH_MODE", "HINT_LIMIT_IN_MEMORY", "HINT_FOLLOW_ON_STRATEGY",
				"HINT_FOLLOW_ON_LOCKING", "HINT_NATIVE_SPACES", "HINT_TIMEOUT"}) {
			result.put(name, new Target(PACKAGE + "HibernateHints", name));
		}
		result.put("HINT_READONLY", new Target(PACKAGE + "HibernateHints", "HINT_READ_ONLY"));
		result.put("HINT_NATIVE_LOCKMODE", new Target(PACKAGE + "HibernateHints", "HINT_NATIVE_LOCK_MODE"));
		result.put("JAKARTA_SPEC_HINT_TIMEOUT", new Target(PACKAGE + "SpecHints", "HINT_SPEC_QUERY_TIMEOUT"));
		for (String name : new String[] {"JAKARTA_HINT_FETCH_GRAPH", "JAKARTA_HINT_FETCHGRAPH"}) {
			result.put(name, new Target(PACKAGE + "SpecHints", "HINT_SPEC_FETCH_GRAPH"));
		}
		for (String name : new String[] {"JAKARTA_HINT_LOAD_GRAPH", "JAKARTA_HINT_LOADGRAPH"}) {
			result.put(name, new Target(PACKAGE + "SpecHints", "HINT_SPEC_LOAD_GRAPH"));
		}
		result.put("HINT_FETCHGRAPH", new Target(PACKAGE + "LegacySpecHints", "HINT_JAVAEE_FETCH_GRAPH"));
		result.put("HINT_LOADGRAPH", new Target(PACKAGE + "LegacySpecHints", "HINT_JAVAEE_LOAD_GRAPH"));
		result.put("SPEC_HINT_TIMEOUT", new Target(PACKAGE + "LegacySpecHints", "HINT_JAVAEE_QUERY_TIMEOUT"));
		return Map.copyOf(result);
	}

	@Override
	public @NonNull String getDisplayName() {
		return "Migrate query-hint constants";
	}

	@Override
	public @NonNull String getDescription() {
		return "Replaces QueryHints constants with HibernateHints, SpecHints, or LegacySpecHints constants, "
				+ "preserving the exact hint keys and supplied values.";
	}

	@Override
	public @NonNull TreeVisitor<?, ExecutionContext> getVisitor() {
		return new MigrationSupport.JavaVisitor(this, skipped) {
			private final Map<UUID, Target> replacements = new HashMap<>();
			private final Set<String> conflicts = new HashSet<>();
			private boolean inherited;
			private boolean retainOrdinaryImport;
			private final Set<String> retainedStaticImports = new HashSet<>();
			private final Set<String> bareFields = new HashSet<>();

			@Override
			public J.@NonNull CompilationUnit visitCompilationUnit(J.@NonNull CompilationUnit cu, @NonNull ExecutionContext ctx) {
				replacements.clear();
				conflicts.clear();
				inherited = false;
				retainOrdinaryImport = false;
				retainedStaticImports.clear();
				bareFields.clear();
				new JavaIsoVisitor<ExecutionContext>() {
					@Override
					public J.@NonNull ClassDeclaration visitClassDeclaration(J.@NonNull ClassDeclaration declaration, @NonNull ExecutionContext context) {
						// Inherited members can shadow generated names without appearing in the source.
						if (declaration.getExtends() != null || declaration.getImplements() != null && !declaration.getImplements().isEmpty()) {
							inherited = true;
						}
						return super.visitClassDeclaration(declaration, context);
					}

					@Override
					public J.@NonNull Identifier visitIdentifier(J.@NonNull Identifier id, @NonNull ExecutionContext context) {
						JavaType.Variable field = id.getFieldType();
						boolean oldField = field != null && TypeUtils.isOfClassType(field.getOwner(), OLD);
						boolean targetField = field != null && MAPPINGS.values().stream().anyMatch(target ->
								TypeUtils.isOfClassType(field.getOwner(), target.owner()) && field.getName().equals(target.field()));
						boolean targetType = id.getFieldType() == null && MAPPINGS.values().stream().anyMatch(target ->
								TypeUtils.isOfClassType(id.getType(), target.owner()));
						if (!oldField && !targetField && !targetType) conflicts.add(id.getSimpleName());
						return super.visitIdentifier(id, context);
					}
				}.visit(cu, ctx);
				J.CompilationUnit analyzed = super.visitCompilationUnit(cu, ctx);
				if (replacements.isEmpty() && cu.getImports().stream().noneMatch(imp -> oldImport(imp))) {
					return analyzed;
				}
				conflicts.addAll(retainedStaticImports);
				// Migrate explicit imports before adding others: an old same-name import
				// would otherwise prevent AddImport from inserting its replacement.
				List<J.Import> imports = new ArrayList<>();
				Map<String, Integer> seen = new HashMap<>();
				for (J.Import imp : analyzed.getImports()) {
					String field = imp.getQualid().getSimpleName();
					Target target = MAPPINGS.get(field);
					if (imp.isStatic() && oldImport(imp) && target != null
							&& bareFields.contains(field) && !retainedStaticImports.contains(field)
							&& !inherited && !conflicts.contains(target.field())) {
						var qualid = imp.getQualid();
						var name = qualid.getName().withSimpleName(target.field());
						if (name.getFieldType() != null) name = rename(name, target);
						imp = imp.withQualid(qualid.withTarget(replacementOwner(qualid.getTarget(), target, true, ctx))
								.withName(name));
					}
					String key = imp.isStatic() + ":" + imp.getTypeName() + ":" + imp.getQualid().getSimpleName();
					Integer previous = seen.putIfAbsent(key, imports.size());
					if (previous == null) imports.add(imp);
					else imports.set(previous, imports.get(previous).withPrefix(
							MigrationSupport.comments(imports.get(previous).getPrefix(), imp.getPrefix())));
				}
				analyzed = analyzed.withImports(imports);
				return (J.CompilationUnit) new JavaVisitor<ExecutionContext>() {
					@Override
					public @NonNull J visitImport(J.@NonNull Import imp, @NonNull ExecutionContext context) {
						return imp;
					}

					@Override
					public @NonNull J visitFieldAccess(J.@NonNull FieldAccess access, @NonNull ExecutionContext context) {
						Target target = replacements.get(access.getId());
						if (target == null) return super.visitFieldAccess(access, context);
						boolean qualified = inherited || conflicts.contains(simple(target.owner()))
								|| access.getTarget() instanceof J.FieldAccess;
						if (!qualified) maybeAddImport(target.owner(), false);
						Expression owner = replacementOwner(access.getTarget(), target, qualified, context);
						return access.withTarget(owner).withName(rename(access.getName(), target));
					}

					@Override
					public @NonNull J visitIdentifier(J.@NonNull Identifier id, @NonNull ExecutionContext context) {
						Target target = replacements.get(id.getId());
						if (target == null) return super.visitIdentifier(id, context);
						J.Identifier name = rename(id, target);
						if (!inherited && !conflicts.contains(target.field())) {
							maybeAddImport(target.owner(), target.field(), false);
							return name;
						}
						Expression owner = TypeTree.build(target.owner()).withType(JavaType.ShallowClass.build(target.owner()));
						return new J.FieldAccess(id.getId(), id.getPrefix(), id.getMarkers(), owner,
								JLeftPadded.build(name.withId(UUID.randomUUID()).withPrefix(Space.EMPTY)), id.getType());
					}

					@Override
					public @NonNull J visitCompilationUnit(J.@NonNull CompilationUnit source, @NonNull ExecutionContext context) {
						// Remove only imports that no skipped reference still requires. This also
						// preserves unresolved bindings and comments on obsolete imports.
						J.CompilationUnit result = (J.CompilationUnit) super.visitCompilationUnit(source, context);
						List<J.Import> kept = new ArrayList<>();
						Space carried = null;
						for (J.Import imp : result.getImports()) {
							boolean remove = oldImport(imp) && (imp.isStatic()
									? !retainedStaticImports.contains(imp.getQualid().getSimpleName())
									&& !("*".equals(imp.getQualid().getSimpleName()) && !retainedStaticImports.isEmpty())
									: !retainOrdinaryImport);
							if (remove) {
								carried = carried == null ? imp.getPrefix() : MigrationSupport.comments(carried, imp.getPrefix());
							}
							else {
								if (carried != null) imp = imp.withPrefix(MigrationSupport.comments(carried, imp.getPrefix()));
								kept.add(imp);
								carried = null;
							}
						}
						result = result.withImports(kept);
						if (carried != null) {
							if (result.getClasses().isEmpty()) result = result.withEof(MigrationSupport.comments(result.getEof(), carried));
							else {
								var classes = new ArrayList<>(result.getClasses());
								classes.set(0, classes.get(0).withPrefix(MigrationSupport.comments(
										carried.withWhitespace(classes.get(0).getPrefix().getWhitespace()), classes.get(0).getPrefix())));
								result = result.withClasses(classes);
							}
						}
						if (cu.printAll().contains("\r\n") && !cu.printAll().replace("\r\n", "").contains("\n")) {
							doAfterVisit(new JavaIsoVisitor<ExecutionContext>() {
								@Override
								public @NonNull Space visitSpace(@NonNull Space space, Space.@NonNull Location location, @NonNull ExecutionContext c) {
									return space.withWhitespace(crlf(space.getWhitespace())).withComments(space.getComments().stream()
											.map(comment -> comment.<Comment>withSuffix(crlf(comment.getSuffix()))).toList());
								}
							});
						}
						return result;
					}
				}.visitNonNull(analyzed, ctx, getCursor().getParentOrThrow());
			}

			@Override
			public J.@NonNull Import visitImport(J.@NonNull Import imp, @NonNull ExecutionContext ctx) {
				return imp;
			}

			@Override
			public J.@NonNull FieldAccess visitFieldAccess(J.@NonNull FieldAccess access, @NonNull ExecutionContext ctx) {
				if ("class".equals(access.getSimpleName()) && explicitOld(access.getTarget())) {
					reject(access, ctx, "UNSUPPORTED_QUERY_HINT_USAGE", "QueryHints class literals require manual migration.");
					return access;
				}
				JavaType.Variable field = access.getName().getFieldType();
				if (field != null && TypeUtils.isOfClassType(field.getOwner(), OLD)) {
					if (!typeReference(access.getTarget())) {
						reject(access, ctx, "UNSUPPORTED_QUERY_HINT_USAGE", "Instance-qualified access must retain evaluation of its receiver.");
					}
					else if (!TypeUtils.isOfClassType(access.getTarget().getType(), OLD)) {
						reject(access, ctx, "MISSING_TYPE_ATTRIBUTION", "Resolve the source owner before migrating this hint reference.");
					}
					else plan(access, field.getName(), ctx);
					return access;
				}
				if (explicitOld(access.getTarget())) {
					reject(access, ctx, "MISSING_TYPE_ATTRIBUTION", "Resolve the source field before migrating this hint reference.");
					return access;
				}
				if (TypeUtils.isOfClassType(access.getType(), OLD) && typeReference(access)) {
					reject(access, ctx, "UNSUPPORTED_QUERY_HINT_USAGE", "Only QueryHints constant references are migrated.");
					return access;
				}
				return super.visitFieldAccess(access, ctx);
			}

			@Override
			public J.@NonNull Identifier visitIdentifier(J.@NonNull Identifier id, @NonNull ExecutionContext ctx) {
				Object parent = getCursor().getParentTreeCursor().getValue();
				if (parent instanceof J.MethodDeclaration declaration && declaration.getName() == id
						|| parent instanceof J.MethodInvocation invocation && invocation.getName() == id
						|| parent instanceof J.MemberReference reference && reference.getReference() == id) {
					return id;
				}
				JavaType.Variable field = id.getFieldType();
				if (field != null && TypeUtils.isOfClassType(field.getOwner(), OLD)) {
					plan(id, field.getName(), ctx);
				}
				else if (explicitOld(id) && id.getFieldType() == null) {
					reject(id, ctx, "UNSUPPORTED_QUERY_HINT_USAGE", "Only QueryHints constant references are migrated.");
				}
				else if (field == null && (id.getType() == null || id.getType() instanceof JavaType.Unknown)
						&& input.getImports().stream().anyMatch(imp -> imp.isStatic()
						&& oldImport(imp) && imp.getQualid().getSimpleName().equals(id.getSimpleName()))) {
					reject(id, ctx, "MISSING_TYPE_ATTRIBUTION", "Resolve the source field before migrating this hint reference.");
				}
				return id;
			}

			@Override
			public J.@NonNull MethodInvocation visitMethodInvocation(J.@NonNull MethodInvocation method, @NonNull ExecutionContext ctx) {
				if (method.getMethodType() != null && TypeUtils.isOfClassType(method.getMethodType().getDeclaringType(), OLD)
						|| method.getSelect() != null && explicitOld(method.getSelect())
						|| method.getMethodType() == null && method.getSelect() == null && input.getImports().stream().anyMatch(imp -> imp.isStatic()
						&& oldImport(imp) && imp.getQualid().getSimpleName().equals(method.getSimpleName()))) {
					reject(method, ctx, "UNSUPPORTED_QUERY_HINT_USAGE", "QueryHints methods require manual migration.");
					// Visit arguments independently, but do not report the supporting owner again.
					return method.withArguments(method.getArguments().stream().map(arg -> (Expression) visitNonNull(arg, ctx)).toList());
				}
				return super.visitMethodInvocation(method, ctx);
			}

			@Override
			public J.@NonNull MemberReference visitMemberReference(J.@NonNull MemberReference reference, @NonNull ExecutionContext ctx) {
				if (explicitOld(reference.getContaining()) || reference.getMethodType() != null
						&& TypeUtils.isOfClassType(reference.getMethodType().getDeclaringType(), OLD)) {
					reject(reference, ctx, "UNSUPPORTED_QUERY_HINT_USAGE", "QueryHints method references require manual migration.");
					return reference;
				}
				return super.visitMemberReference(reference, ctx);
			}

			private boolean explicitOld(Expression expression) {
				if (TypeUtils.isOfClassType(expression.getType(), OLD) && typeReference(expression)) return true;
				if (expression.getType() != null && !(expression.getType() instanceof JavaType.Unknown)) return false;
				String spelling = qualifiedName(expression);
				return OLD.equals(spelling) || "QueryHints".equals(spelling) && input.getImports().stream()
						.anyMatch(imp -> !imp.isStatic() && oldImport(imp));
			}

			private void plan(J candidate, String field, ExecutionContext ctx) {
				String spelling = candidate instanceof J.Identifier id ? id.getSimpleName() : ((J.FieldAccess) candidate).getSimpleName();
				if (!field.equals(spelling)) {
					reject(candidate, ctx, "MISSING_TYPE_ATTRIBUTION", "The source field attribution is inconsistent with its spelling.");
					return;
				}
				Target target = MAPPINGS.get(field);
				if (target == null) reject(candidate, ctx, "UNMAPPED_QUERY_HINT_CONSTANT", "No value-preserving mapping exists for this source field.");
				else {
					replacements.put(candidate.getId(), target);
					if (candidate instanceof J.Identifier) bareFields.add(field);
				}
			}

			private void reject(J candidate, ExecutionContext ctx, String reason, String message) {
				if (candidate instanceof J.Identifier id && !"QueryHints".equals(id.getSimpleName())) {
					retainedStaticImports.add(id.getSimpleName());
				}
				if (candidate instanceof J.MethodInvocation method && method.getSelect() == null) {
					retainedStaticImports.add(method.getSimpleName());
				}
				new JavaIsoVisitor<ExecutionContext>() {
					@Override
					public J.@NonNull Identifier visitIdentifier(J.@NonNull Identifier id, @NonNull ExecutionContext context) {
						if ("QueryHints".equals(id.getSimpleName()) && id.getFieldType() == null
								&& !(getCursor().getParentTreeCursor().getValue() instanceof J.FieldAccess access && access.getName() == id)) {
							retainOrdinaryImport = true;
						}
						return id;
					}
				}.visit(candidate, ctx, getCursor().getParentOrThrow());
				skip(candidate, ctx, "QueryHints", reason, message);
			}
		};
	}

	private static boolean typeReference(Expression expression) {
		if (expression instanceof J.Identifier id) return id.getFieldType() == null;
		if (expression instanceof J.FieldAccess access) {
			return access.getName().getFieldType() == null && typeReference(access.getTarget());
		}
		return false;
	}

	private static boolean oldImport(J.Import imp) {
		// Unused static method imports may lack attribution and getTypeName()
		// then treats the method as a nested class. Import spelling is unambiguous.
		return OLD.equals(qualifiedName(imp.isStatic() ? imp.getQualid().getTarget() : imp.getQualid()));
	}

	private static String qualifiedName(Expression expression) {
		if (expression instanceof J.Identifier id) return id.getSimpleName();
		if (expression instanceof J.FieldAccess access) return qualifiedName(access.getTarget()) + "." + access.getSimpleName();
		return "";
	}

	private static Expression replacementOwner(Expression original, Target target, boolean qualified, ExecutionContext ctx) {
		Space[] comments = {Space.EMPTY};
		new JavaIsoVisitor<ExecutionContext>() {
			@Override
			public @NonNull Space visitSpace(@NonNull Space space, Space.@NonNull Location location, @NonNull ExecutionContext context) {
				comments[0] = MigrationSupport.comments(comments[0], space);
				return space;
			}
		}.visit(original, ctx);
		Expression owner = TypeTree.build(qualified ? target.owner() : simple(target.owner()));
		return owner.withType(JavaType.ShallowClass.build(target.owner()))
				.withPrefix(original.getPrefix().withComments(comments[0].getComments()));
	}

	private static String simple(String owner) {
		return owner.substring(owner.lastIndexOf('.') + 1);
	}

	private static String crlf(String whitespace) {
		return whitespace.replace("\r\n", "\n").replace("\n", "\r\n");
	}

	private static J.Identifier rename(J.Identifier id, Target target) {
		return id.withSimpleName(target.field()).withFieldType(id.getFieldType()
				.withOwner(JavaType.ShallowClass.build(target.owner())).withName(target.field()));
	}
}
