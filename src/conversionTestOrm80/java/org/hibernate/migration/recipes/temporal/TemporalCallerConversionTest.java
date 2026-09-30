package org.hibernate.migration.recipes.temporal;

import org.hibernate.migration.testing.ApiValidation;
import org.hibernate.migration.testing.MigrationSources;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/// Verifies caller propagation and connected-property rejection.
/// @author Steve Ebersole
class TemporalCallerConversionTest {
    @Test void accessorsAndClientsAcrossFiles() {
        var sources = new LinkedHashMap<String, String>();
        sources.put("fixture/temporal/temporalcallerconversion/accessorsandclientsacrossfiles/Client.java", MigrationSources.configured().read("fixture/temporal/temporalcallerconversion/accessorsandclientsacrossfiles/Client.java"));
        sources.put("fixture/temporal/temporalcallerconversion/accessorsandclientsacrossfiles/Entity.java", MigrationSources.configured().read("fixture/temporal/temporalcallerconversion/accessorsandclientsacrossfiles/Entity.java"));
        var result = ApiValidation.run(new MigrateTemporalAnnotation(), sources, ApiValidation.environments().context("orm74"));
        assertTrue(result.skipped().isEmpty(), result.skipped().toString());
        assertTrue(result.files().get("fixture/temporal/temporalcallerconversion/accessorsandclientsacrossfiles/Entity.java").contains("java.time.Instant getCreated"));
        assertTrue(result.files().get("fixture/temporal/temporalcallerconversion/accessorsandclientsacrossfiles/Client.java").contains("java.time.Instant first"));
        var reversed = new LinkedHashMap<String, String>();
        reversed.put("fixture/temporal/temporalcallerconversion/accessorsandclientsacrossfiles/Entity.java", sources.get("fixture/temporal/temporalcallerconversion/accessorsandclientsacrossfiles/Entity.java"));
        reversed.put("fixture/temporal/temporalcallerconversion/accessorsandclientsacrossfiles/Client.java", sources.get("fixture/temporal/temporalcallerconversion/accessorsandclientsacrossfiles/Client.java"));
        assertEquals(result.files(), ApiValidation.run(new MigrateTemporalAnnotation(), reversed, ApiValidation.environments().context("orm74")).files());
    }

    @Test void getterAnnotationAndPropertyTransfers() {
        var sourceFiles = MigrationSources.configured().files("fixture/temporal/temporalcallerconversion/getterannotationandpropertytransfers/Example.java");
        String source = sourceFiles.get("fixture/temporal/temporalcallerconversion/getterannotationandpropertytransfers/Example.java");
        var result = ApiValidation.run(new MigrateTemporalAnnotation(), sourceFiles, "orm74");
        assertTrue(result.skipped().isEmpty(), result.skipped().toString());
        assertFalse(result.text().contains("@Temporal"));
        assertFalse(result.text().contains("Function<"));
    }

    @Test void unsupportedClientBlocksConnectedProperties() {
        var sourceFiles = MigrationSources.configured().files("fixture/temporal/temporalcallerconversion/unsupportedclientblocksconnectedproperties/Example.java");
        String source = sourceFiles.get("fixture/temporal/temporalcallerconversion/unsupportedclientblocksconnectedproperties/Example.java");
        var result = ApiValidation.run(new MigrateTemporalAnnotation(), sourceFiles, "orm74");
        assertEquals(source, result.text());
        assertEquals(2, result.skipped().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"methodreference", "reassignedlocal", "escapedlocal", "capturedlocal", "objectlocal", "arraylocal", "mutableresult"})
    void unsupportedClientsAreAtomic(String scenario) {
        var sourceFiles = MigrationSources.configured().scenario("fixture/temporal/temporalcallerconversion/unsupportedclientsareatomic/" + scenario);
        String source = sourceFiles.values().iterator().next();
        var result = ApiValidation.run(new MigrateTemporalAnnotation(), sourceFiles, "orm74");
        assertEquals(source, result.text());
        assertEquals(1, result.skipped().size());
    }

    @Test void incompatibleTransferBlocksOnlyConnectedProperties() {
        var result = ApiValidation.run(configured(MigrateTemporalAnnotation.TimestampTarget.INSTANT), MigrationSources.configured().files("fixture/temporal/temporalcallerconversion/incompatibletransferblocksonlyconnectedproperties/Example.java"), "orm74");
        assertEquals(2, result.skipped().size());
        assertTrue(result.text().contains("@Temporal(TemporalType.DATE)\n\tjava.util.Date day"));
        assertTrue(result.text().contains("@Temporal(TemporalType.TIMESTAMP)\n\tjava.util.Date stamp"));
        assertTrue(result.text().contains("java.time.LocalTime unrelated"));
    }

    @Test void blockedCrossFileOrderHasStableDiagnostics() {
        var sources = new LinkedHashMap<String, String>();
        sources.put("fixture/temporal/temporalcallerconversion/blockedcrossfileorderhasstablediagnostics/Client.java", MigrationSources.configured().read("fixture/temporal/temporalcallerconversion/blockedcrossfileorderhasstablediagnostics/Client.java"));
        sources.put("fixture/temporal/temporalcallerconversion/blockedcrossfileorderhasstablediagnostics/Entity.java", MigrationSources.configured().read("fixture/temporal/temporalcallerconversion/blockedcrossfileorderhasstablediagnostics/Entity.java"));
        var first = ApiValidation.run(new MigrateTemporalAnnotation(), sources, ApiValidation.environments().context("orm74"));
        var reversed = new LinkedHashMap<String, String>();
        reversed.put("fixture/temporal/temporalcallerconversion/blockedcrossfileorderhasstablediagnostics/Entity.java", sources.get("fixture/temporal/temporalcallerconversion/blockedcrossfileorderhasstablediagnostics/Entity.java"));
        reversed.put("fixture/temporal/temporalcallerconversion/blockedcrossfileorderhasstablediagnostics/Client.java", sources.get("fixture/temporal/temporalcallerconversion/blockedcrossfileorderhasstablediagnostics/Client.java"));
        var second = ApiValidation.run(new MigrateTemporalAnnotation(), reversed, ApiValidation.environments().context("orm74"));
        assertEquals(sources, first.files());
        assertEquals(first.files(), second.files());
        assertEquals(first.skipped().getFirst().getReasonCode(), second.skipped().getFirst().getReasonCode());
        assertEquals(first.skipped().getFirst().getMessage(), second.skipped().getFirst().getMessage());
        assertTrue(first.skipped().getFirst().getMessage().contains("fixture/temporal/temporalcallerconversion/blockedcrossfileorderhasstablediagnostics/Client.java"));
    }

    @Test void crossFileWritesAreConvertedAndReadsBlock() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fixture/temporal/temporalcallerconversion/crossfilewritesareconvertedandreadsblock/Example.java", MigrationSources.configured().read("fixture/temporal/temporalcallerconversion/crossfilewritesareconvertedandreadsblock/Example.java"));
        sources.put("fixture/temporal/temporalcallerconversion/crossfilewritesareconvertedandreadsblock/Writer.java", MigrationSources.configured().read("fixture/temporal/temporalcallerconversion/crossfilewritesareconvertedandreadsblock/Writer.java"));
        var result = ApiValidation.run(new MigrateTemporalAnnotation(), sources, "orm74");
        assertTrue(result.skipped().isEmpty());
        assertTrue(result.files().get("fixture/temporal/temporalcallerconversion/crossfilewritesareconvertedandreadsblock/Writer.java").contains(".apply(d)"));
        sources.put("fixture/temporal/temporalcallerconversion/crossfilewritesareconvertedandreadsblock/Reader.java", MigrationSources.configured().read("fixture/temporal/temporalcallerconversion/crossfilewritesareconvertedandreadsblock/Reader.java"));
        result = ApiValidation.run(new MigrateTemporalAnnotation(), sources, "orm74");
        assertEquals(sources, result.files());
        assertEquals(1, result.skipped().size());
    }

    @Test void assignmentExpressionsAndMutableCallsBlockWholeField() {
        var sourceFiles = MigrationSources.configured().files("fixture/temporal/temporalcallerconversion/assignmentexpressionsandmutablecallsblockwholefield/Example.java");
        String source = sourceFiles.get("fixture/temporal/temporalcallerconversion/assignmentexpressionsandmutablecallsblockwholefield/Example.java");
        var result = ApiValidation.run(configured(MigrateTemporalAnnotation.TimestampTarget.INSTANT), sourceFiles, "orm74");
        assertEquals(source, result.text());
        assertEquals(2, result.skipped().size());
    }

    private MigrateTemporalAnnotation configured(MigrateTemporalAnnotation.TimestampTarget target) {
        return new MigrateTemporalAnnotation(target, true, "+02:00", "Europe/Paris",
                MigrateTemporalAnnotation.LocalTimezoneSource.ZONE_ID);
    }


    @ParameterizedTest
    @EnumSource(MigrateTemporalAnnotation.TimestampTarget.class)
    void coordinatedConversion(MigrateTemporalAnnotation.TimestampTarget target) {
        var sources = new LinkedHashMap<String, String>();
        sources.put("fixture/temporal/temporalcallerconversion/coordinatedconversion/Client.java", MigrationSources.configured().read("fixture/temporal/temporalcallerconversion/coordinatedconversion/Client.java"));
        sources.put("fixture/temporal/temporalcallerconversion/coordinatedconversion/Entity.java", MigrationSources.configured().read("fixture/temporal/temporalcallerconversion/coordinatedconversion/Entity.java"));
        var recipe = new MigrateTemporalAnnotation(target, true, "+02:00", "Europe/Paris",
                MigrateTemporalAnnotation.LocalTimezoneSource.ZONE_ID);
        var result = ApiValidation.run(recipe, sources, "orm74");
        assertTrue(result.skipped().isEmpty());
        String targetType = "java.time." + switch (target) {
            case INSTANT -> "Instant";
            case LOCAL -> "LocalDateTime";
            case OFFSET -> "OffsetDateTime";
            case ZONED -> "ZonedDateTime";
        };
        String entity = result.files().get("fixture/temporal/temporalcallerconversion/coordinatedconversion/Entity.java");
        assertFalse(entity.contains("@Temporal"));
        assertTrue(entity.contains("java.time.LocalDate day ="));
        assertTrue(entity.contains("java.time.LocalTime time ="));
        assertTrue(entity.contains("private " + targetType + " created;"));
        assertTrue(entity.contains("public " + targetType + " getCreated()"));
        assertTrue(entity.contains("setCreated(" + targetType + " value)"));
        assertTrue(entity.contains(targetType + " calendar ="));
        assertTrue(entity.contains("/* precision */"));
        String client = result.files().get("fixture/temporal/temporalcallerconversion/coordinatedconversion/Client.java");
        assertTrue(client.contains("java.util.Date input"), "Unrelated caller parameter stays legacy");
        assertTrue(client.contains(".apply(input)"), "Legacy argument is converted at the setter boundary");
        assertTrue(client.contains(targetType + " first = e.getCreated();"));
        assertTrue(client.contains("final " + targetType + " alias = first;"));
        assertTrue(client.contains("if (alias != null)\n\t\t\te.setCreated(alias);"), "Converted aliases pass through");
        var reversed = new LinkedHashMap<String, String>();
        reversed.put("fixture/temporal/temporalcallerconversion/coordinatedconversion/Entity.java", sources.get("fixture/temporal/temporalcallerconversion/coordinatedconversion/Entity.java"));
        reversed.put("fixture/temporal/temporalcallerconversion/coordinatedconversion/Client.java", sources.get("fixture/temporal/temporalcallerconversion/coordinatedconversion/Client.java"));
        var reversedResult = ApiValidation.run(recipe, reversed, "orm74");
        assertEquals(result.files(), reversedResult.files());
        assertTrue(reversedResult.skipped().isEmpty());
    }


}
