package org.hibernate.migration.recipes.orm80;

import java.util.List;

import org.hibernate.migration.testing.ApiValidation;
import org.hibernate.migration.testing.MigrationSources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.TypeTree;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies option relocation against the real source and target APIs.
///
/// @author Steve Ebersole
class MigrateFindMultipleOptionsTest {
	private static final String RECIPE = "org.hibernate.migration.recipes.orm80.MigrateFindMultipleOptions";
	private static final List<String> OPTIONS = List.of("OrderingMode", "SessionCheckMode", "RemovalsMode", "BatchSize");

	@ParameterizedTest
	@ValueSource(strings = {RECIPE, "org.hibernate.migration.recipes.orm80"})
	void migratesOptionsAndPreservesCalls(String recipe) {
		String path = "fixture/findmultiple/calls/Example.java";
		var sources = MigrationSources.configured().files(path);
		var result = ApiValidation.run(ApiValidation.composite(recipe), sources, "orm74");
		for (String option : OPTIONS) {
			assertTrue(result.text().contains("import org.hibernate.FindMultipleOption." + option + ";")
					|| result.text().contains("import static org.hibernate.FindMultipleOption." + option + ".*;"), result.text());
			assertFalse(result.text().contains("import org.hibernate." + option + ";"));
		}
		assertTrue(result.text().contains("import static org.hibernate.FindMultipleOption.OrderingMode.*;"), result.text());
		assertTrue(result.text().contains("import static org.hibernate.FindMultipleOption.SessionCheckMode.*;"));
		assertTrue(result.text().contains("import static org.hibernate.FindMultipleOption.RemovalsMode.*;"));
		String source = sources.get(path);
		assertEquals(source.substring(source.indexOf("class Example")), result.text().substring(result.text().indexOf("class Example")));
		assertTrue(result.skipped().isEmpty());
	}

	@ParameterizedTest
	@ValueSource(strings = {"wildcard", "qualified", "multifile"})
	void migratesReferenceForms(String scenario) {
		var sources = MigrationSources.configured().scenario("fixture/findmultiple/" + scenario);
		var result = ApiValidation.run(ApiValidation.composite(RECIPE), sources, "orm74");
		for (String option : OPTIONS) {
			assertFalse(result.text().replace("\"org.hibernate.BatchSize\"", "").contains("org.hibernate." + option), result.text());
		}
		if (scenario.equals("qualified")) {
			assertTrue(result.text().contains("String name = \"org.hibernate.BatchSize\";"));
		}
		if (scenario.equals("wildcard")) {
			assertTrue(result.text().contains("Session session;"));
		}
		assertTrue(result.skipped().isEmpty());
	}

	@Test
	void conflictsPreserveUnrelatedBindings() {
		var sources = MigrationSources.configured().scenario("fixture/findmultiple/conflicts");
		var result = ApiValidation.run(ApiValidation.composite(RECIPE), sources, "orm74");
		for (String option : OPTIONS) {
			assertTrue(result.text().contains("org.hibernate.FindMultipleOption." + option), result.text());
		}
		assertTrue(result.text().contains("import org.hibernate.annotations.BatchSize;"));
		assertTrue(result.text().contains("@BatchSize(size = 20)"));
		assertTrue(result.text().contains("return org.hibernate.BatchSize;"));
		assertTrue(result.text().contains("OrderingMode unrelatedOrdering;"));
		assertTrue(result.text().contains("SessionCheckMode unrelatedChecking;"));
		assertTrue(result.text().contains("RemovalsMode unrelatedRemovals;"));
	}

	@Test
	void unrelatedTypesAreUnchanged() {
		var sources = MigrationSources.configured().scenario("fixture/findmultiple/unrelated");
		assertEquals(sources, ApiValidation.run(ApiValidation.composite(RECIPE), sources, "orm74").files());
	}

	@Test
	void mixedOldAndNewReferencesConverge() {
		String source = """
				import org.hibernate.FindMultipleOption;
				import org.hibernate.FindMultipleOption.BatchSize;
				class Example {
					org.hibernate.BatchSize oldBatch = new org.hibernate.BatchSize(4);
					FindMultipleOption.BatchSize currentBatch = new FindMultipleOption.BatchSize(8);
					BatchSize directlyImported = new BatchSize(10);
				}
				""";
		var result = ApiValidation.run(ApiValidation.composite(RECIPE), source, "orm80");
		assertFalse(result.text().contains("org.hibernate.BatchSize"));
		assertTrue(result.text().contains("FindMultipleOption.BatchSize currentBatch = new FindMultipleOption.BatchSize(8);"));
		assertTrue(result.text().contains("BatchSize directlyImported = new BatchSize(10);"));
		assertTrue(result.text().contains("import org.hibernate.FindMultipleOption;"));
		assertTrue(result.text().contains("import org.hibernate.FindMultipleOption.BatchSize;"));
	}

	@Test
	void alreadyMigratedInputIsUnchanged() {
		String source = """
				import org.hibernate.FindMultipleOption.*;
				class Example {
					OrderingMode ordering = OrderingMode.ORDERED;
					SessionCheckMode checking = SessionCheckMode.ENABLED;
					RemovalsMode removals = RemovalsMode.INCLUDE;
					BatchSize batch = new BatchSize(10);
				}
				""";
		assertEquals(source, ApiValidation.run(ApiValidation.composite(RECIPE), source, "orm80").text());
	}

	@Test
	void missingAttributionInMixedInputIsPreserved() {
		String input = """
				class Example {
					org.hibernate.OrderingMode supported;
					org.hibernate.OrderingMode unresolved;
				}
				""";
		var ctx = new InMemoryExecutionContext(t -> fail(t));
		var parsed = ApiValidation.parser("orm74").build().parse(ctx, input).findFirst().orElseThrow();
		var stripped = (J.CompilationUnit) new JavaIsoVisitor<Integer>() {
			@Override
			public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations declarations, Integer p) {
				if (declarations.getVariables().getFirst().getSimpleName().equals("unresolved")) {
					return declarations.withTypeExpression((TypeTree) new JavaIsoVisitor<Integer>() {
						@Override
						public J.Identifier visitIdentifier(J.Identifier identifier, Integer param) {
							return super.visitIdentifier(identifier, param).withType(null);
						}

						@Override
						public J.FieldAccess visitFieldAccess(J.FieldAccess access, Integer param) {
							return super.visitFieldAccess(access, param).withType(null);
						}
					}.visitNonNull(declarations.getTypeExpression(), p));
				}
				return super.visitVariableDeclarations(declarations, p);
			}
		}.visitNonNull(parsed, 0);
		var run = new MigrateFindMultipleOptions().run(new InMemoryLargeSourceSet(List.of(stripped)), ctx);
		assertEquals(1, run.getChangeset().size());
		String output = run.getChangeset().getAllResults().getFirst().getAfter().printAll();
		assertTrue(output.contains("org.hibernate.OrderingMode unresolved;"), output);
		assertTrue(output.contains("org.hibernate.FindMultipleOption.OrderingMode supported;"), output);
	}

	@Test
	void missingAttributionDoesNotAuthorizeNameBasedConversion() {
		String input = """
				class Example {
					org.hibernate.OrderingMode ordering;
					org.hibernate.SessionCheckMode checking;
					org.hibernate.RemovalsMode removals;
					org.hibernate.BatchSize batch;
				}
				""";
		var ctx = new InMemoryExecutionContext(t -> fail(t));
		var parsed = ApiValidation.parser("orm74").build().parse(ctx, input).findFirst().orElseThrow();
		var stripped = (J.CompilationUnit) new JavaIsoVisitor<Integer>() {
			@Override
			public J.Identifier visitIdentifier(J.Identifier identifier, Integer p) {
				return super.visitIdentifier(identifier, p).withType(null);
			}

			@Override
			public J.FieldAccess visitFieldAccess(J.FieldAccess access, Integer p) {
				return super.visitFieldAccess(access, p).withType(null);
			}

			@Override
			public J.VariableDeclarations.NamedVariable visitVariable(J.VariableDeclarations.NamedVariable variable, Integer p) {
				return super.visitVariable(variable, p).withVariableType(null);
			}
		}.visitNonNull(parsed, 0);
		var run = new MigrateFindMultipleOptions().run(new InMemoryLargeSourceSet(List.of(stripped)), ctx);
		assertEquals(0, run.getChangeset().size());
	}
}
