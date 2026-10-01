package org.hibernate.migration.recipes.orm80;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.hibernate.migration.recipes.support.MigrationSupport;
import org.hibernate.migration.recipes.table.SkippedMigrations;
import org.jspecify.annotations.NonNull;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.tree.Comment;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JRightPadded;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.TypeUtils;

/// Replaces legacy custom-SQL result checks while preserving explicit verification.
///
/// @author Steve Ebersole
public class MigrateSqlResultVerification extends Recipe {
	private static final String CHECK = "org.hibernate.annotations.ResultCheckStyle";
	private static final String EXPECTATION = "org.hibernate.jdbc.Expectation";
	private static final Set<String> ANNOTATIONS = Set.of("SQLInsert", "SQLUpdate", "SQLDelete", "SQLDeleteAll");
	// The template parser is independent of the application's source API classpath.
	private static final String TEMPLATE_API = """
			package org.hibernate.jdbc;
			public interface Expectation {
				class None implements Expectation {}
				class RowCount implements Expectation {}
				class OutParameter implements Expectation {}
			}
			""";
	private final transient SkippedMigrations skipped = new SkippedMigrations(this);

	@Override
	public @NonNull String getDisplayName() {
		return "Migrate SQL result verification";
	}

	@Override
	public @NonNull String getDescription() {
		return "Replaces check with verify on Hibernate custom-SQL annotations, preserving explicit verification.";
	}

	@Override
	public @NonNull TreeVisitor<?, ExecutionContext> getVisitor() {
		return new MigrationSupport.JavaVisitor(this, skipped) {
			private boolean qualified;

			@Override
			public J.@NonNull CompilationUnit visitCompilationUnit(J.@NonNull CompilationUnit cu, @NonNull ExecutionContext ctx) {
				boolean[] conflict = {false};
				new JavaIsoVisitor<boolean[]>() {
					@Override
					public J.@NonNull ClassDeclaration visitClassDeclaration(J.@NonNull ClassDeclaration declaration, boolean @NonNull [] found) {
						// An inherited member type may shadow an import without a local reference.
						// The Java type model does not expose all inherited nested types.
						if (declaration.getExtends() != null || declaration.getImplements() != null && !declaration.getImplements().isEmpty()) {
							found[0] = true;
						}
						return super.visitClassDeclaration(declaration, found);
					}

					@Override
					public J.@NonNull Identifier visitIdentifier(J.@NonNull Identifier id, boolean @NonNull [] found) {
						if ("Expectation".equals(id.getSimpleName()) && !TypeUtils.isOfClassType(id.getType(), EXPECTATION)) {
							found[0] = true;
						}
						return super.visitIdentifier(id, found);
					}
				}.visit(cu, conflict);
				qualified = conflict[0];
				J.CompilationUnit result = super.visitCompilationUnit(cu, ctx);
				if (cu.printAll().contains("\r\n") && !cu.printAll().replace("\r\n", "").contains("\n")) {
					// Import insertion uses LF; keep the incoming file's CRLF convention.
					doAfterVisit(new JavaIsoVisitor<ExecutionContext>() {
						@Override
						public @NonNull Space visitSpace(Space space, Space.@NonNull Location location, @NonNull ExecutionContext context) {
							return space.withWhitespace(space.getWhitespace().replaceAll("(?<!\\r)\\n", "\r\n"));
						}
					});
				}
				return result;
			}

			@Override
			public J.@NonNull Annotation visitAnnotation(J.@NonNull Annotation annotation, @NonNull ExecutionContext ctx) {
				J.Annotation ann = super.visitAnnotation(annotation, ctx);
				String name = ann.getSimpleName();
				if (!ANNOTATIONS.contains(name)) return ann;
				String fqn = "org.hibernate.annotations." + name;
				if (!TypeUtils.isOfClassType(ann.getType(), fqn)) {
					if ((ann.getType() == null || ann.getType() instanceof JavaType.Unknown)
							&& MigrationSupport.explicitType(ann, fqn, input)) {
						skip(ann, ctx, name, "MISSING_TYPE_ATTRIBUTION", "Resolve the source Hibernate API before migrating this annotation.");
					}
					return ann;
				}
				List<Expression> args = ann.getArguments();
				if (args == null) return ann;
				int check = -1;
				boolean verify = false;
				boolean malformed = false;
				Set<String> members = new HashSet<>();
				for (int i = 0; i < args.size(); i++) {
					if (!(args.get(i) instanceof J.Assignment a) || !(a.getVariable() instanceof J.Identifier id)) {
						malformed = true;
						continue;
					}
					String member = id.getSimpleName();
					if (!members.add(member)) malformed = true;
					if ("check".equals(member)) check = i;
					if ("verify".equals(member)) verify = true;
				}
				if (check < 0) return ann;
				if (malformed) {
					skip(ann, ctx, name, "UNSUPPORTED_ANNOTATION_SHAPE", "Expected distinct named annotation members.");
					return ann;
				}
				J.Assignment assignment = (J.Assignment) args.get(check);
				if (verify) {
					ann = removeCheck(ann, check);
				}
				else {
					String target = expectation(assignment.getAssignment());
					if (target == null) {
						skip(ann, ctx, name, "SQL_CHECK_VALUE_UNRESOLVED", "Expected a resolved ResultCheckStyle NONE, COUNT, or PARAM constant.");
						return ann;
					}
					Expression oldValue = assignment.getAssignment();
					String owner = qualified ? EXPECTATION : "Expectation";
					JavaTemplate template = JavaTemplate.builder(owner + "." + target + ".class")
							.imports(EXPECTATION).javaParser(JavaParser.fromJavaVersion().dependsOn(TEMPLATE_API)).build();
					Expression value = template.apply(new Cursor(new Cursor(getCursor(), assignment), oldValue), oldValue.getCoordinates().replace());
					value = value.withPrefix(oldValue.getPrefix().withComments(commentsOf(oldValue)));
					J.Identifier key = (J.Identifier) assignment.getVariable();
					JavaType.Variable field = key.getFieldType();
					if (field != null) field = field.withName("verify").withType(value.getType());
					List<Expression> changed = new ArrayList<>(args);
					changed.set(check, assignment.withVariable(key.withSimpleName("verify").withFieldType(field).withType(value.getType()))
							.withAssignment(value).withType(value.getType()));
					ann = ann.withArguments(changed);
					if (!qualified) maybeAddImport(EXPECTATION);
				}
				maybeRemoveImport(CHECK);
				for (String constant : List.of("NONE", "COUNT", "PARAM")) maybeRemoveImport(CHECK + "." + constant);
				return ann;
			}

			private String expectation(Expression value) {
				J.Identifier id = value instanceof J.FieldAccess access ? access.getName()
						: value instanceof J.Identifier identifier ? identifier : null;
				if (id == null || id.getFieldType() == null || !TypeUtils.isOfClassType(id.getFieldType().getOwner(), CHECK)
						|| !TypeUtils.isOfClassType(id.getType(), CHECK)) return null;
				return switch (id.getSimpleName()) {
					case "NONE" -> "None";
					case "COUNT" -> "RowCount";
					case "PARAM" -> "OutParameter";
					default -> null;
				};
			}

			private List<Comment> commentsOf(J tree) {
				List<Comment> comments = new ArrayList<>();
				new JavaIsoVisitor<List<Comment>>() {
					@Override
					public @NonNull Space visitSpace(Space space, Space.@NonNull Location location, @NonNull List<Comment> found) {
						found.addAll(space.getComments());
						return space;
					}
				}.visit(tree, comments);
				return comments;
			}

			private J.Annotation removeCheck(J.Annotation ann, int index) {
				var container = ann.getPadding().getArguments();
				List<JRightPadded<Expression>> elements = new ArrayList<>(container.getPadding().getElements());
				var removed = elements.remove(index);
				List<Comment> comments = commentsOf(removed.getElement());
				comments.addAll(removed.getAfter().getComments());
				if (index < elements.size()) {
					var next = elements.get(index);
					comments.addAll(next.getElement().getPrefix().getComments());
					elements.set(index, next.withElement(next.getElement().withPrefix(next.getElement().getPrefix().withComments(comments))));
				}
				else {
					var previous = elements.get(elements.size() - 1);
					List<Comment> closing = new ArrayList<>(previous.getAfter().getComments());
					closing.addAll(comments);
					elements.set(elements.size() - 1, previous.withAfter(removed.getAfter().withComments(closing)));
				}
				return ann.getPadding().withArguments(container.getPadding().withElements(elements));
			}
		};
	}
}
