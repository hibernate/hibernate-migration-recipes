package org.hibernate.migration.recipes.orm80;

import java.util.List;
import java.util.Map;

import org.hibernate.migration.recipes.table.SkippedMigrations;
import org.hibernate.migration.testing.ApiValidation;
import org.hibernate.migration.testing.MigrationSources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.java.JavaParser;

import static org.junit.jupiter.api.Assertions.*;

/// Validates SQL verification against the source and target Hibernate APIs.
///
/// @author Steve Ebersole
class MigrateSqlResultVerificationTest {
	private static final String RECIPE = "org.hibernate.migration.recipes.orm80.MigrateSqlResultVerification";
	private static final String ROOT = "fixture/sqlverification/";

	@ParameterizedTest
	@ValueSource(strings = {RECIPE, "org.hibernate.migration.recipes.orm80"})
	void allAnnotationAndValueCombinations(String recipe) {
		var result = ApiValidation.run(ApiValidation.composite(recipe), MigrationSources.configured().files(ROOT + "Matrix.java"), "orm74");
		assertTrue(result.skipped().isEmpty());
		assertFalse(result.text().contains("check ="));
		assertFalse(result.text().contains("ResultCheckStyle"));
		assertTrue(result.text().contains("import org.hibernate.jdbc.Expectation;"));
		for (String ann : List.of("SQLInsert", "SQLUpdate", "SQLDelete", "SQLDeleteAll")) {
			for (String expected : List.of("None", "RowCount", "OutParameter")) {
				assertTrue(result.text().contains("@" + ann + "(sql = \"statement\", verify = Expectation." + expected + ".class, callable = true)"), result.text());
			}
		}
	}

	@Test
	void formsPrecedenceAndComments() {
		var result = ApiValidation.run(new MigrateSqlResultVerification(), MigrationSources.configured().files(ROOT + "Forms.java"), "orm74");
		String text = result.text();
		assertTrue(result.skipped().isEmpty());
		assertFalse(text.contains("check ="));
		assertTrue(text.contains("verify = Expectation.None.class"));
		assertTrue(text.contains("verify = Expectation.class"));
		assertTrue(text.contains("verify = Custom.class"));
		assertTrue(text.contains("@SQLUpdate(sql = \"unchanged\")"));
		assertTrue(text.contains("@SQLDelete(sql = \"already\", verify = Expectation.RowCount.class)"));
		for (String comment : List.of("before", "inside", "tail", "last check", "closing", "key", "equals", "value", "constant", "after")) {
			assertEquals(1, occurrences(text, "/* " + comment + " */"), text);
		}
	}

	@Test
	void conflictsUseQualifiedNamesAndUnusedImportsAreRemoved() {
		var result = ApiValidation.run(new MigrateSqlResultVerification(), MigrationSources.configured().files(ROOT + "Conflict.java"), "orm74");
		assertTrue(result.text().contains("verify = org.hibernate.jdbc.Expectation.RowCount.class"), result.text());
		assertFalse(result.text().contains("import org.hibernate.jdbc.Expectation;"));
		assertFalse(result.text().contains("ResultCheckStyle"));
	}

	@Test
	void unrelatedAnnotationsAndRetainedEnumUses() {
		var sources = MigrationSources.configured().files(ROOT + "Unrelated.java");
		var result = ApiValidation.run(new MigrateSqlResultVerification(), sources, "orm74");
		assertEquals(sources, result.files());
		// ORM 8 removes this enum; surviving uses deliberately remain outside this recipe's scope.
		var retained = MigrationSources.configured().files(ROOT + "Retained.java");
		ApiValidation.compile(retained, "orm74");
		var ctx = new InMemoryExecutionContext(t -> fail(t));
		var parsed = ApiValidation.parser("orm74").build().parse(ctx, retained.get(ROOT + "Retained.java")).toList();
		var run = new MigrateSqlResultVerification().run(new InMemoryLargeSourceSet(parsed), ctx);
		assertEquals(1, run.getChangeset().size());
		String text = run.getChangeset().getAllResults().getFirst().getAfter().printAll();
		assertTrue(text.contains("import org.hibernate.annotations.ResultCheckStyle;"));
		assertTrue(text.contains("import static org.hibernate.annotations.ResultCheckStyle.COUNT;"));
		assertTrue(text.contains("ResultCheckStyle retained = COUNT;"));
		assertTrue(text.contains("verify = Expectation.RowCount.class"));
		assertTrue(run.getDataTableRows(SkippedMigrations.class).isEmpty());
	}

	@Test
	void removesUnusedExplicitStaticImports() {
		var result = ApiValidation.run(new MigrateSqlResultVerification(), MigrationSources.configured().files(ROOT + "Imported.java"), "orm74");
		assertFalse(result.text().contains("ResultCheckStyle"));
		assertFalse(result.text().contains("check ="));
	}

	@Test
	void alreadyMigratedAnnotationsRemainExactlyUnchanged() {
		var sources = MigrationSources.configured().files(ROOT + "Already.java");
		assertEquals(sources, ApiValidation.run(new MigrateSqlResultVerification(), sources, "orm74").files());
	}

	@Test
	void inheritedMemberTypeCannotShadowTheGeneratedReference() {
		var sources = MigrationSources.configured().scenario(ROOT + "inherited");
		var result = ApiValidation.run(new MigrateSqlResultVerification(), sources, "orm74");
		assertTrue(result.text().contains("verify = org.hibernate.jdbc.Expectation.RowCount.class"));
		assertFalse(result.text().contains("import org.hibernate.jdbc.Expectation;"));
	}

	@Test
	void crlfIsPreserved() {
		String source = MigrationSources.configured().read(ROOT + "Matrix.java").replace("\n", "\r\n");
		var result = ApiValidation.run(new MigrateSqlResultVerification(), Map.of(ROOT + "Matrix.java", source), "orm74");
		assertFalse(result.text().replace("\r\n", "").contains("\n"));
	}

	@Test
	void malformedAndUnresolvedValuesAreReportedAtOriginalLocations() {
		assertSkipped("@org.hibernate.annotations.SQLInsert(sql=\"x\", check=org.hibernate.annotations.ResultCheckStyle.COUNT, check=org.hibernate.annotations.ResultCheckStyle.NONE) class Example {}",
				"UNSUPPORTED_ANNOTATION_SHAPE", false);
		assertSkipped("@org.hibernate.annotations.SQLInsert(sql=\"x\", check=unknown) class Example {}",
				"SQL_CHECK_VALUE_UNRESOLVED", false);
		assertSkipped("@org.hibernate.annotations.SQLInsert(sql=\"x\", check=unknown) class Example {}",
				"MISSING_TYPE_ATTRIBUTION", true);
	}

	@Test
	void explicitVerifyWinsEvenWhenCheckValueIsUnresolved() {
		String source = "@org.hibernate.annotations.SQLInsert(sql=\"x\", check=unknown, verify=org.hibernate.jdbc.Expectation.class) class Example {}";
		var ctx = new InMemoryExecutionContext(t -> fail(t));
		var parsed = ApiValidation.parser("orm74").build().parse(ctx, source).toList();
		var run = new MigrateSqlResultVerification().run(new InMemoryLargeSourceSet(parsed), ctx);
		assertEquals(1, run.getChangeset().size());
		assertFalse(run.getChangeset().getAllResults().getFirst().getAfter().printAll().contains("check="));
		assertTrue(run.getDataTableRows(SkippedMigrations.class).isEmpty());
	}

	private void assertSkipped(String annotation, String reason, boolean missingApi) {
		String source = "// original location\n" + annotation;
		var ctx = new InMemoryExecutionContext(t -> fail(t));
		var parser = missingApi ? JavaParser.fromJavaVersion() : ApiValidation.parser("orm74");
		var parsed = parser.build().parse(ctx, source).toList();
		var run = new MigrateSqlResultVerification().run(new InMemoryLargeSourceSet(parsed), ctx);
		assertEquals(0, run.getChangeset().size());
		var rows = run.getDataTableRows(SkippedMigrations.class);
		assertEquals(1, rows.size());
		assertEquals(reason, rows.getFirst().getReasonCode());
		assertEquals(2, rows.getFirst().getLine());
		assertEquals(1, rows.getFirst().getColumn());
		assertEquals(RECIPE, rows.getFirst().getRecipe());
		assertEquals("SQLInsert", rows.getFirst().getSubject());
		assertEquals("Example.java", rows.getFirst().getSourcePath());
	}

	private static int occurrences(String source, String fragment) {
		return (source.length() - source.replace(fragment, "").length()) / fragment.length();
	}
}
