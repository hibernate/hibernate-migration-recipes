package org.hibernate.migration.recipes.orm80;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.hibernate.migration.recipes.table.SkippedMigrations;
import org.hibernate.migration.testing.ApiValidation;
import org.hibernate.migration.testing.MigrationSources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.RecipeRun;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies query-hint migration against isolated source and target APIs.
///
/// @author Steve Ebersole
class MigrateQueryHintsTest {
	private static final String RECIPE = "org.hibernate.migration.recipes.orm80.MigrateQueryHints";
	private static final String ROOT = "fixture/queryhints/";

	@ParameterizedTest
	@ValueSource(strings = {RECIPE, "org.hibernate.migration.recipes.orm80"})
	void migratesEveryConstant(String recipe) {
		var result = ApiValidation.run(ApiValidation.composite(recipe), sources("matrix"), "orm74");
		assertTrue(result.skipped().isEmpty());
		assertFalse(result.text().contains("QueryHints"));
		for (var target : MigrateQueryHints.MAPPINGS.values()) {
			assertTrue(result.text().contains(simple(target.owner()) + "." + target.field()), result.text());
		}
	}

	@Test
	void mappingCoversEverySourceConstantAndPreservesActualKeys() throws Exception {
		try (var source = loader("orm74"); var target = loader("orm80")) {
			Class<?> old = source.loadClass("org.hibernate.jpa.QueryHints");
			Set<String> fields = Arrays.stream(old.getFields()).filter(field -> field.getType() == String.class)
					.map(java.lang.reflect.Field::getName).collect(Collectors.toSet());
			assertEquals(21, fields.size());
			assertEquals(fields, MigrateQueryHints.MAPPINGS.keySet());
			for (var entry : MigrateQueryHints.MAPPINGS.entrySet()) {
				var replacement = entry.getValue();
				assertEquals(old.getField(entry.getKey()).get(null),
						target.loadClass(replacement.owner()).getField(replacement.field()).get(null), entry.getKey());
			}
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"qualified", "statics", "wildcard"})
	void handlesEveryReferenceAndImportForm(String scenario) {
		var result = ApiValidation.run(new MigrateQueryHints(), sources(scenario), "orm74");
		assertTrue(result.skipped().isEmpty());
		assertFalse(result.text().contains("QueryHints"), result.text());
		if (scenario.equals("qualified")) {
			assertEquals(21, occurrences(result.text(), ", \"value\");"));
			assertTrue(result.text().contains("org.hibernate.jpa.HibernateHints.HINT_READ_ONLY"));
		}
		if (scenario.equals("statics")) {
			assertTrue(result.text().contains("import static org.hibernate.jpa.HibernateHints.HINT_READ_ONLY;"), result.text());
			assertEquals(1, occurrences(result.text(), "import static org.hibernate.jpa.SpecHints.HINT_SPEC_FETCH_GRAPH;"));
		}
		if (scenario.equals("wildcard")) assertTrue(result.text().contains("AvailableHints other;"));
	}

	@Test
	void preservesCommentsAnnotationsAndExistingBindings() {
		var result = ApiValidation.run(new MigrateQueryHints(), sources("forms"), "orm74");
		assertTrue(result.skipped().isEmpty());
		for (String comment : List.of("before", "owner", "field", "tail", "package")) {
			assertEquals(1, occurrences(result.text(), "/* " + comment + " */"), result.text());
		}
		assertTrue(result.text().contains("name = HibernateHints.HINT_READ_ONLY, value = \"true\""), result.text());
		assertTrue(result.text().contains("String existing = HibernateHints.HINT_READ_ONLY;"));
		assertTrue(result.text().contains("String existingGraph = HINT_SPEC_FETCH_GRAPH;"));
		assertTrue(result.text().contains("String existingQualified = SpecHints.HINT_SPEC_FETCH_GRAPH;"));
		assertTrue(result.text().contains("\"org.hibernate.jpa.QueryHints.HINT_READONLY\""));
		assertEquals(1, occurrences(result.text(), "import static org.hibernate.jpa.SpecHints.HINT_SPEC_FETCH_GRAPH;"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"conflicts", "inherited"})
	void qualifiesConflictingBindings(String scenario) {
		var result = ApiValidation.run(new MigrateQueryHints(), sources(scenario), "orm74");
		assertTrue(result.skipped().isEmpty());
		assertFalse(result.text().contains("QueryHints"));
		assertTrue(result.text().contains("org.hibernate.jpa.HibernateHints.HINT_READ_ONLY"));
		assertFalse(result.text().contains("import org.hibernate.jpa.HibernateHints;"));
		assertTrue(result.text().contains("HINT_READ_ONLY = \"unrelated\";"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"unrelated", "already"})
	void unrelatedAndAlreadyMigratedTypesRemainExactlyUnchanged(String scenario) {
		var input = sources(scenario);
		var result = ApiValidation.run(new MigrateQueryHints(), input, "orm74");
		assertEquals(input, result.files());
		assertTrue(result.skipped().isEmpty());
	}

	@Test
	void localMethodShadowsAnUnusedStaticImportWithoutADiagnostic() {
		var input = sources("shadowedmethod");
		var result = ApiValidation.run(new MigrateQueryHints(), input, "orm74");
		assertTrue(result.skipped().isEmpty());
		assertFalse(result.text().contains("import static org.hibernate.jpa.QueryHints"));
		String original = input.values().iterator().next();
		assertEquals(original.substring(original.indexOf("class Example")), result.text().substring(result.text().indexOf("class Example")));
	}

	@Test
	void partialMigrationPreservesUnsupportedUsesAndReportsOriginalCoordinates() {
		var files = sources("unsupported");
		ApiValidation.compile(files, "orm74");
		String input = files.values().iterator().next();
		String path = files.keySet().iterator().next();
		var run = raw(input, false, path);
		String output = run.getChangeset().getAllResults().getFirst().getAfter().printAll();
		assertTrue(output.contains("String supported = HINT_READ_ONLY;"), output);
		for (String line : List.of("QueryHints receiver;", "QueryHints.class;", "QueryHints.getDefinedHints();",
				"getDefinedHints();", "receiver.HINT_READONLY;", "receiver().HINT_READONLY;", "QueryHints receiver()")) {
			assertTrue(output.contains(line), output);
		}
		assertTrue(output.contains("import org.hibernate.jpa.QueryHints;"));
		assertTrue(output.contains("import static org.hibernate.jpa.QueryHints.getDefinedHints;"));
		assertFalse(output.contains("import static org.hibernate.jpa.QueryHints.HINT_READONLY;"));
		assertTrue(output.contains("QueryHints::getDefinedHints;"));
		var rows = run.getDataTableRows(SkippedMigrations.class);
		assertEquals(8, rows.size(), rows.toString());
		for (var row : rows) {
			assertEquals(path, row.getSourcePath());
			assertEquals(RECIPE, row.getRecipe());
			assertEquals("UNSUPPORTED_QUERY_HINT_USAGE", row.getReasonCode());
			assertEquals("QueryHints", row.getSubject());
			String line = input.lines().toList().get(row.getLine() - 1);
			assertTrue(line.substring(row.getColumn() - 1).startsWith("QueryHints")
					|| line.substring(row.getColumn() - 1).startsWith("getDefinedHints")
					|| line.substring(row.getColumn() - 1).startsWith("receiver"), line);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"org.hibernate.jpa.QueryHints.HINT_READONLY",
			"QueryHints.HINT_READONLY",
			"HINT_READONLY"
	})
	void missingAttributionIsReportedWithoutChangingTheSource(String expression) {
		String imports = expression.startsWith("org.") ? "// no import required\n" : expression.startsWith("QueryHints.")
				? "import org.hibernate.jpa.QueryHints;\n" : "import static org.hibernate.jpa.QueryHints.HINT_READONLY;\n";
		String source = imports + "// original coordinates\n"
				+ "class Example {\n\tString hint = " + expression + ";\n}\n";
		var run = raw(source, true);
		assertEquals(0, run.getChangeset().size());
		var rows = run.getDataTableRows(SkippedMigrations.class);
		assertEquals(1, rows.size());
		assertEquals("MISSING_TYPE_ATTRIBUTION", rows.getFirst().getReasonCode());
		assertEquals(4, rows.getFirst().getLine());
		assertEquals(16, rows.getFirst().getColumn());
	}

	@ParameterizedTest
	@ValueSource(strings = {"missing", "name", "owner"})
	void incompleteOrInconsistentFieldAttributionIsReported(String mode) {
		String source = "class Example { String hint = org.hibernate.jpa.QueryHints.HINT_READONLY; }";
		var ctx = new InMemoryExecutionContext(t -> fail(t));
		var parsed = ApiValidation.parser("orm74").build().parse(ctx, source).map(file ->
				(org.openrewrite.SourceFile) new JavaIsoVisitor<InMemoryExecutionContext>() {
					@Override
					public J.Identifier visitIdentifier(J.Identifier id, InMemoryExecutionContext context) {
						if (id.getSimpleName().equals("HINT_READONLY") && id.getFieldType() != null) {
							return id.withFieldType(switch (mode) {
								case "missing" -> null;
								case "name" -> id.getFieldType().withName("different");
								default -> id.getFieldType().withOwner(JavaType.ShallowClass.build("unrelated.Hints"));
								});
						}
						return super.visitIdentifier(id, context);
					}
				}.visitNonNull(file, ctx)).toList();
		var run = new MigrateQueryHints().run(new InMemoryLargeSourceSet(parsed), ctx);
		assertEquals(0, run.getChangeset().size());
		var rows = run.getDataTableRows(SkippedMigrations.class);
		assertEquals(1, rows.size());
		assertEquals("MISSING_TYPE_ATTRIBUTION", rows.getFirst().getReasonCode());
	}

	@Test
	void crlfAndImportCommentsArePreserved() {
		String path = ROOT + "forms/Example.java";
		String input = sources("forms").get(path).replace("import org.hibernate.jpa.QueryHints;",
				"// before import\nimport org.hibernate.jpa.QueryHints; // after import").replace("\n", "\r\n");
		var result = ApiValidation.run(new MigrateQueryHints(), Map.of(path, input), "orm74");
		assertFalse(result.text().replace("\r\n", "").contains("\n"), result.text());
		assertEquals(1, occurrences(result.text(), "// before import"));
		assertEquals(1, occurrences(result.text(), "// after import"));
	}

	@Test
	void unmappedAttributedFieldsAreReported() {
		String source = "class Example { String hint = org.hibernate.jpa.QueryHints.HINT_READONLY; }";
		var ctx = new InMemoryExecutionContext(t -> fail(t));
		var parsed = ApiValidation.parser("orm74").build().parse(ctx, source).map(file ->
				(org.openrewrite.SourceFile) new JavaIsoVisitor<InMemoryExecutionContext>() {
					@Override
					public J.Identifier visitIdentifier(J.Identifier id, InMemoryExecutionContext context) {
						if (id.getFieldType() != null && id.getSimpleName().equals("HINT_READONLY")) {
							return id.withSimpleName("FUTURE_HINT").withFieldType(id.getFieldType().withName("FUTURE_HINT"));
						}
						return super.visitIdentifier(id, context);
					}
				}.visitNonNull(file, ctx)).toList();
		var run = new MigrateQueryHints().run(new InMemoryLargeSourceSet(parsed), ctx);
		assertEquals(0, run.getChangeset().size());
		assertEquals("UNMAPPED_QUERY_HINT_CONSTANT", run.getDataTableRows(SkippedMigrations.class).getFirst().getReasonCode());
	}

	private static RecipeRun raw(String source, boolean missingApi) {
		return raw(source, missingApi, "Example.java");
	}

	private static RecipeRun raw(String source, boolean missingApi, String path) {
		var ctx = new InMemoryExecutionContext(t -> fail(t));
		var parser = missingApi ? JavaParser.fromJavaVersion() : ApiValidation.parser("orm74");
		return new MigrateQueryHints().run(new InMemoryLargeSourceSet(parser.build().parse(ctx, source)
				.map(file -> file.<org.openrewrite.SourceFile>withSourcePath(Path.of(path))).toList()), ctx);
	}

	private static Map<String, String> sources(String scenario) {
		return MigrationSources.configured().scenario(ROOT + scenario);
	}

	private static URLClassLoader loader(String api) throws Exception {
		URL[] urls = ApiValidation.environments().paths(api).stream().map(path -> {
			try { return path.toUri().toURL(); }
			catch (java.net.MalformedURLException e) { throw new IllegalArgumentException(e); }
		}).toArray(URL[]::new);
		return new URLClassLoader(urls, null);
	}

	private static String simple(String owner) {
		return owner.substring(owner.lastIndexOf('.') + 1);
	}

	private static int occurrences(String text, String fragment) {
		return (text.length() - text.replace(fragment, "").length()) / fragment.length();
	}
}
