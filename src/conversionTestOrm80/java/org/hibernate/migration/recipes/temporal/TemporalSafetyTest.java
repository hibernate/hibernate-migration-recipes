package org.hibernate.migration.recipes.temporal;

import org.hibernate.migration.testing.ApiValidation;
import org.hibernate.migration.testing.MigrationSources;
import org.hibernate.migration.recipes.table.SkippedMigrations;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/// Verifies name resolution and accessor hierarchy safety before temporal conversion.
/// @author Steve Ebersole
class TemporalSafetyTest {
    @Test void importedJavaNameMustNotProduceInvalidOutput() {
        var sources = MigrationSources.configured().files("fixture/temporal/temporalsafety/importedjavanamemustnotproduceinvalidoutput/p/java.java", "fixture/temporal/temporalsafety/importedjavanamemustnotproduceinvalidoutput/Example.java");
        var outcome = ApiValidation.run(new MigrateTemporalAnnotation(), sources, ApiValidation.environments().context("orm74"));
        assertEquals(sources, outcome.files());
        assertEquals("NAME_RESOLUTION_CONFLICT", outcome.skipped().getFirst().getReasonCode());
    }

    @Test void newSignatureMustNotOverrideInheritedFinalMethod() {
        var sourceFiles = MigrationSources.configured().files("fixture/temporal/temporalsafety/newsignaturemustnotoverrideinheritedfinalmethod/Example.java");
        String source = sourceFiles.get("fixture/temporal/temporalsafety/newsignaturemustnotoverrideinheritedfinalmethod/Example.java");
        var outcome = ApiValidation.run(new MigrateTemporalAnnotation(), sourceFiles, "orm74");
        assertEquals(source, outcome.text());
        assertEquals("UNSUPPORTED_TEMPORAL_HIERARCHY", outcome.skipped().getFirst().getReasonCode());
    }
    @ParameterizedTest
    @ValueSource(strings = {"packagewildcard", "nestedtype", "nestedwildcard", "staticnestedtype", "staticnestedwildcard"})
    void importedTypesNamedJava(String scenario) {
        var sources = MigrationSources.configured().files("fixture/temporal/temporalsafety/importedtypesnamedjava/" + scenario + "/Example.java",
                "fixture/temporal/temporalsafety/importedtypesnamedjava/p/java.java", "fixture/temporal/temporalsafety/importedtypesnamedjava/p/Parent.java");
        var result = ApiValidation.run(new MigrateTemporalAnnotation(), sources, ApiValidation.environments().context("orm74"));
        assertEquals(sources, result.files());
        assertEquals("NAME_RESOLUTION_CONFLICT", result.skipped().getFirst().getReasonCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"field", "membertype"})
    void inheritedJavaName(String scenario) {
        var sources = MigrationSources.configured().scenario("fixture/temporal/temporalsafety/inheritedjavaname/" + scenario);
        var result = ApiValidation.run(new MigrateTemporalAnnotation(), sources, ApiValidation.environments().context("orm74"));
        assertEquals(sources, result.files());
        assertEquals("NAME_RESOLUTION_CONFLICT", result.skipped().getFirst().getReasonCode());
    }

    @Test void samePackageJavaName() {
        var sources = MigrationSources.configured().files("fixture/temporal/temporalsafety/samepackagejavaname/p/java.java", "fixture/temporal/temporalsafety/samepackagejavaname/p/Example.java");
        var result = ApiValidation.run(new MigrateTemporalAnnotation(), sources, ApiValidation.environments().context("orm74"));
        assertEquals(sources, result.files());
        assertEquals("NAME_RESOLUTION_CONFLICT", result.skipped().getFirst().getReasonCode());
    }

    @Test void callerImportBlocksPropertyAtomically() {
        var sources = MigrationSources.configured().files("fixture/temporal/temporalsafety/callerimportblockspropertyatomically/p/java.java", "fixture/temporal/temporalsafety/callerimportblockspropertyatomically/Entity.java", "fixture/temporal/temporalsafety/callerimportblockspropertyatomically/Client.java");
        var result = ApiValidation.run(new MigrateTemporalAnnotation(), sources, ApiValidation.environments().context("orm74"));
        assertEquals(sources, result.files());
        assertEquals("NAME_RESOLUTION_CONFLICT", result.skipped().getFirst().getReasonCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"instancemethod", "staticmethod", "defaultmethod", "genericmethod"})
    void inheritedOverloadMustNotBecomeAnOverride(String scenario) {
        var sourceFiles = MigrationSources.configured().scenario("fixture/temporal/temporalsafety/inheritedoverloadmustnotbecomeanoverride/" + scenario);
        String source = sourceFiles.values().iterator().next();
        var result = ApiValidation.run(new MigrateTemporalAnnotation(), sourceFiles, "orm74");
        assertEquals(source, result.text());
        assertEquals("UNSUPPORTED_TEMPORAL_HIERARCHY", result.skipped().getFirst().getReasonCode());
    }

    @Test void descendantOverloadBlocksBaseProperty() {
        var sourceFiles = MigrationSources.configured().files("fixture/temporal/temporalsafety/descendantoverloadblocksbaseproperty/Example.java");
        String source = sourceFiles.get("fixture/temporal/temporalsafety/descendantoverloadblocksbaseproperty/Example.java");
        var result = ApiValidation.run(new MigrateTemporalAnnotation(), sourceFiles, "orm74");
        assertEquals(source, result.text());
        assertEquals("UNSUPPORTED_TEMPORAL_HIERARCHY", result.skipped().getFirst().getReasonCode());
    }

    @Test void privateAncestorMethodDoesNotBlockConversion() {
        var result = ApiValidation.run(new MigrateTemporalAnnotation(), MigrationSources.configured().files("fixture/temporal/temporalsafety/privateancestormethoddoesnotblockconversion/Example.java"), "orm74");
        assertTrue(result.skipped().isEmpty());
        assertTrue(result.text().contains("public void setCreated(java.time.Instant input)"));
    }

    @Test void importedNameConflict() {
        var sources = MigrationSources.configured().files("fixture/temporal/temporalsafety/importednameconflict/p/java.java", "fixture/temporal/temporalsafety/importednameconflict/Example.java");
        var recipe = new MigrateTemporalAnnotation();
        var result = ApiValidation.run(recipe, sources, "orm74");
        assertEquals(sources, result.files());
        assertEquals(1, result.skipped().size());
        assertDiagnostic(result.skipped().getFirst(), 8, 2, "NAME_RESOLUTION_CONFLICT");
        var reversed = new LinkedHashMap<String, String>();
        List.copyOf(sources.entrySet()).reversed()
                .forEach(entry -> reversed.put(entry.getKey(), entry.getValue()));
        var reversedResult = ApiValidation.run(recipe, reversed, "orm74");
        assertEquals(result.files(), reversedResult.files());
        assertEquals(1, reversedResult.skipped().size());
        assertDiagnostic(reversedResult.skipped().getFirst(), 8, 2, "NAME_RESOLUTION_CONFLICT");
        assertEquals(result.skipped().getFirst().getMessage(), reversedResult.skipped().getFirst().getMessage());
    }

    private void assertDiagnostic(SkippedMigrations.Row row, int line, int column, String reason) {
        assertNotNull(row);
        assertEquals(MigrateTemporalAnnotation.class.getName(), row.getRecipe());
        assertEquals("fixture/temporal/temporalsafety/importednameconflict/Example.java", row.getSourcePath());
        assertEquals("Temporal", row.getSubject());
        assertEquals(line, row.getLine());
        assertEquals(column, row.getColumn());
        assertEquals(reason, row.getReasonCode());
        assertFalse(row.getMessage().isBlank());
    }
}
