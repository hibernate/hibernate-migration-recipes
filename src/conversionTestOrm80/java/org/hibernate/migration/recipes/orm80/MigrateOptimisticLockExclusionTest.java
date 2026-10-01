package org.hibernate.migration.recipes.orm80;

import java.util.List;
import java.util.ArrayList;
import org.hibernate.migration.testing.ApiValidation;
import org.hibernate.migration.testing.MigrationSources;
import org.hibernate.migration.recipes.table.SkippedMigrations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.RecipeRun;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.java.JavaParser;
import static org.junit.jupiter.api.Assertions.*;

/// Checks source/target compilation, conservative defaults, comments, and diagnostics.
/// @author Steve Ebersole
class MigrateOptimisticLockExclusionTest {
	private static final String RECIPE = "org.hibernate.migration.recipes.orm80.MigrateOptimisticLockExclusion";

	@ParameterizedTest
	@ValueSource(strings = {RECIPE, "org.hibernate.migration.recipes.orm80"})
	void migratesFieldsAndGettersAndReportsUncertainInclusion(String recipe) {
		var sources = MigrationSources.configured().files("fixture/optimistic/Examples.java");
		var result = ApiValidation.run(ApiValidation.composite(recipe), sources, "orm74");
		String text = result.text();
		assertTrue(text.contains("@ExcludedFromVersioning"), text);
		for (String name : List.of("included", "values", "owned", "ownedMany")) {
			int end = text.indexOf(" " + name + ";");
			String declaration = text.substring(text.lastIndexOf(";", end - 1) + 1, end);
			assertFalse(declaration.contains("@OptimisticLock"), declaration);
		}
		assertFalse(text.contains("@OptimisticLock(excluded = false)\n\tpublic String getLabel"), text);
		assertTrue(text.contains("@ExcludedFromVersioning\n\tpublic String getNotes"), text);
		for (String comment : List.of("argument", "equal", "value", "tail")) {
			assertEquals(1, occurrences(text, "/*" + comment + "*/"), text);
		}
		assertEquals(7, result.skipped().size(), result.skipped().toString());
		assertEquals(6, result.skipped().stream().filter(r -> r.getReasonCode().equals("OPTIMISTIC_LOCK_DEFAULT_UNVERIFIED")).count());
		assertEquals(1, result.skipped().stream().filter(r -> r.getReasonCode().equals("OPTIMISTIC_LOCK_VALUE_UNSUPPORTED")).count());
		String original = sources.values().iterator().next();
		for (var row : result.skipped()) {
			assertEquals("fixture/optimistic/Examples.java", row.getSourcePath());
			assertEquals(2, row.getColumn());
			assertTrue(original.lines().toList().get(row.getLine() - 1).contains("@OptimisticLock"));
		}
	}

	@Test
	void qualifiedNamesAndUnrelatedAnnotationRemainDistinct() {
		var result = ApiValidation.run(new MigrateOptimisticLockExclusion(),
				MigrationSources.configured().files("fixture/optimistic/Qualified.java"), "orm74");
		assertTrue(result.text().contains("@jakarta.persistence.ExcludedFromVersioning"));
		assertTrue(result.text().contains("@OptimisticLock(excluded = true)\n\tString unrelated"));
		assertTrue(result.skipped().isEmpty());
	}

	@Test
	void wildcardImportsAreAttributed() {
		var result = ApiValidation.run(new MigrateOptimisticLockExclusion(),
				MigrationSources.configured().files("fixture/optimistic/Wildcard.java"), "orm74");
		assertTrue(result.text().contains("@ExcludedFromVersioning"));
		assertTrue(result.skipped().isEmpty());
	}

	@Test
	void commentsSurviveRemovalAndRenamingExactlyOnce() {
		var result = ApiValidation.run(new MigrateOptimisticLockExclusion(),
				MigrationSources.configured().files("fixture/optimistic/Comments.java"), "orm74");
		for (String comment : List.of("name", "before", "equals", "literal", "end", "replacementName", "getter")) {
			assertEquals(1, occurrences(result.text(), "/*" + comment + "*/"), result.text());
		}
		assertFalse(result.text().contains("OptimisticLock"), result.text());
		assertTrue(result.text().contains("import jakarta.persistence.ExcludedFromVersioning;"), result.text());
		assertTrue(result.skipped().isEmpty());
	}

	@Test
	void customMappingsAmbiguousOwnershipAndNameCollisions() {
		var sources = MigrationSources.configured().files("fixture/optimistic/Safety.java");
		var result = ApiValidation.run(new MigrateOptimisticLockExclusion(), sources, "orm74");
		assertEquals(6, result.skipped().size());
		assertTrue(result.skipped().stream().allMatch(r -> r.getReasonCode().equals("OPTIMISTIC_LOCK_DEFAULT_UNVERIFIED")));
		assertEquals(2, occurrences(result.text(), "@jakarta.persistence.ExcludedFromVersioning"), result.text());
		assertTrue(result.text().contains("import org.hibernate.annotations.OptimisticLock;"));
		assertTrue(result.text().contains("Class<?> legacy = OptimisticLock.class;"));
	}

	@Test
	void redundantExclusionAndContradictoryInclusion() {
		String source = """
				import org.hibernate.annotations.OptimisticLock;
				import jakarta.persistence.ExcludedFromVersioning;
				class Example {
				    @OptimisticLock(excluded = true) @ExcludedFromVersioning String redundant;
				    @OptimisticLock(excluded = false) @ExcludedFromVersioning String conflict;
				    @ExcludedFromVersioning String migrated;
				}
				""";
		var result = ApiValidation.run(new MigrateOptimisticLockExclusion(), source, ApiValidation.environments().primary().target());
		assertFalse(result.text().contains("@OptimisticLock(excluded = true)"));
		assertTrue(result.text().contains("@OptimisticLock(excluded = false) @ExcludedFromVersioning String conflict"));
		assertEquals(1, result.skipped().size());
		assertEquals("OPTIMISTIC_LOCK_CONFLICT", result.skipped().get(0).getReasonCode());
		assertEquals(5, result.skipped().get(0).getLine());
	}

	@Test
	void invalidShapesAndDuplicatesRemainUnchanged() {
		String source = """
				import org.hibernate.annotations.OptimisticLock;
				class Example {
				    @OptimisticLock String missing;
				    @OptimisticLock(excluded = true, excluded = false) String duplicateArgument;
				    @OptimisticLock(excluded = true) @OptimisticLock(excluded = false) String duplicateAnnotation;
				}
				""";
		var run = raw(source, true);
		assertEquals(0, run.getChangeset().size());
		assertEquals(4, run.getDataTableRows(SkippedMigrations.class).size());
		assertEquals(2, run.getDataTableRows(SkippedMigrations.class).stream().filter(r -> r.getReasonCode().equals("OPTIMISTIC_LOCK_CONFLICT")).count());
	}

	@Test
	void missingAttributionIsReported() {
		String source = "import org.hibernate.annotations.OptimisticLock; class Example { @OptimisticLock(excluded = true) String value; }";
		var run = raw(source, false);
		assertEquals(0, run.getChangeset().size());
		assertEquals("MISSING_TYPE_ATTRIBUTION", run.getDataTableRows(SkippedMigrations.class).get(0).getReasonCode());
	}

	private RecipeRun raw(String source, boolean attributed) {
		List<Throwable> errors = new ArrayList<>();
		var ctx = new InMemoryExecutionContext(errors::add);
		var builder = attributed ? ApiValidation.parser("orm74") : JavaParser.fromJavaVersion();
		var parsed = builder.build().parse(ctx, source).toList();
		var run = new MigrateOptimisticLockExclusion().run(new InMemoryLargeSourceSet(parsed), ctx);
		assertTrue(errors.isEmpty(), errors.toString());
		return run;
	}

	private static int occurrences(String text, String value) {
		return (text.length() - text.replace(value, "").length()) / value.length();
	}
}
