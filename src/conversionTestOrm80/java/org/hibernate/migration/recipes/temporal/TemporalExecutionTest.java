package org.hibernate.migration.recipes.temporal;

import org.hibernate.migration.testing.ApiValidation;
import org.hibernate.migration.testing.MigrationSources;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.java.tree.J;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies execution isolation and option snapshot boundaries through the recipe lifecycle.
/// @author Steve Ebersole
class TemporalExecutionTest {
    private static final String SOURCE_PATH = "fixture/temporal/temporalexecution/source/Example.java";
    private static final Map<String, String> SOURCE_FILES = MigrationSources.configured().files(SOURCE_PATH);
    private static final String SOURCE = SOURCE_FILES.get(SOURCE_PATH);

    @Test void oneRecipeCanRunOnIndependentInputs() {
        var recipe = new MigrateTemporalAnnotation();
        var first = ApiValidation.run(recipe, SOURCE_FILES, "orm74");
        var rejectedFiles = MigrationSources.configured().files("fixture/temporal/temporalexecution/onerecipecanrunonindependentinputs/Example.java");
        String rejected = rejectedFiles.get("fixture/temporal/temporalexecution/onerecipecanrunonindependentinputs/Example.java");
        var middle = ApiValidation.run(recipe, rejectedFiles, "orm74");
        assertEquals(rejected, middle.text());
        assertEquals(1, middle.skipped().size());
        var last = ApiValidation.run(recipe, SOURCE_FILES, "orm74");
        assertEquals(first.files(), last.files());
        assertTrue(last.skipped().isEmpty());
    }

    @Test void laterExecutionSeesNewOptions() {
        var recipe = new MigrateTemporalAnnotation();
        assertTrue(ApiValidation.run(recipe, SOURCE_FILES, "orm74").text().contains("java.time.Instant created"));
        recipe.timestampTarget = MigrateTemporalAnnotation.TimestampTarget.OFFSET;
        recipe.offset = "Z";
        var next = ApiValidation.run(recipe, SOURCE_FILES, "orm74");
        assertTrue(next.text().contains("java.time.OffsetDateTime created"));
        assertTrue(next.text().contains("ZoneOffset.of(\"Z\")"));
    }

    @Test void optionsAreCapturedAtExecutionStart() {
        var recipe = new MigrateTemporalAnnotation();
        var ctx = new InMemoryExecutionContext(error -> { throw new AssertionError(error); });
        var execution = recipe.getInitialValue(ctx);
        recipe.timestampTarget = MigrateTemporalAnnotation.TimestampTarget.ZONED;
        J.CompilationUnit input = (J.CompilationUnit) ApiValidation.parser("orm74").build().parse(ctx, SOURCE).findFirst().orElseThrow();
        recipe.getScanner(execution).visit(input, ctx);
        J.CompilationUnit output = (J.CompilationUnit) recipe.getVisitor(execution).visit(input, ctx);
        assertNotNull(output);
        assertTrue(output.printAll().contains("java.time.Instant created"));
        ApiValidation.compile(Map.of(SOURCE_PATH, output.printAll()), ApiValidation.environments().primary().target());
    }
}
