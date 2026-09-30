package org.hibernate.migration.recipes.temporal;

import org.hibernate.migration.testing.ApiValidation;
import org.hibernate.migration.testing.MigrationSources;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import org.openrewrite.config.YamlResourceLoader;
import org.openrewrite.config.Environment;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/// Verifies option binding and value-conversion policies.
/// @author Steve Ebersole
class TemporalConfigurationTest {
    @Test
    void nullSetterArgumentNeedsNoTimezone() {
        var result = ApiValidation.run(new MigrateTemporalAnnotation(), MigrationSources.configured().files("fixture/temporal/temporalconfiguration/nullsetterargumentneedsnotimezone/Example.java"), "orm74");
        assertTrue(result.skipped().isEmpty());
        assertFalse(result.text().contains("Function<"));
        assertTrue(result.text().contains("java.time.LocalDate value"), result.text());
    }

    @ParameterizedTest
    @EnumSource(MigrateTemporalAnnotation.LocalTimezoneSource.class)
    void localValuePolicy(MigrateTemporalAnnotation.LocalTimezoneSource policy) {
        var recipe = new MigrateTemporalAnnotation(MigrateTemporalAnnotation.TimestampTarget.LOCAL,
                false, "-07:00", "America/Denver", policy);
        var result = ApiValidation.run(recipe, MigrationSources.configured().files("fixture/temporal/temporalconfiguration/localvaluepolicy/Example.java"), "orm74");
        assertTrue(result.skipped().isEmpty());
        String expected = policy == MigrateTemporalAnnotation.LocalTimezoneSource.SYSTEM ? "systemDefault()"
                : policy == MigrateTemporalAnnotation.LocalTimezoneSource.OFFSET ? "ZoneOffset.of(\"-07:00\")"
                : "ZoneId.of(\"America/Denver\")";
        assertTrue(result.text().contains(expected), result.text());
        assertFalse(result.text().contains("getTimeZone()"));
        assertTrue(result.text().contains("java.time.LocalDate day"), result.text());
        assertTrue(result.text().contains("java.time.LocalTime time"), result.text());
        assertTrue(result.text().contains("java.time.LocalDateTime stamp"), result.text());
    }

    private static final String CUSTOM_TIMEZONE_SOURCE_PATH = "fixture/temporal/temporalconfiguration/custom_timezone_source/Example.java";
    private static final Map<String, String> CUSTOM_TIMEZONE_SOURCE_FILES = MigrationSources.configured().files(CUSTOM_TIMEZONE_SOURCE_PATH);
    private static final String CUSTOM_TIMEZONE_SOURCE = CUSTOM_TIMEZONE_SOURCE_FILES.get(CUSTOM_TIMEZONE_SOURCE_PATH);

    @Test
    void honoredCustomCalendarTimezoneRequiresReview() {
        var result = ApiValidation.run(configured(MigrateTemporalAnnotation.TimestampTarget.ZONED), CUSTOM_TIMEZONE_SOURCE_FILES, "orm74");
        assertEquals(CUSTOM_TIMEZONE_SOURCE, result.text());
        assertEquals(1, result.skipped().size());
        assertEquals("UNSUPPORTED_CALENDAR_TIMEZONE", result.skipped().getFirst().getReasonCode());
    }

    @Test
    void instantConversionDoesNotNeedCustomCalendarTimezone() {
        var result = ApiValidation.run(configured(MigrateTemporalAnnotation.TimestampTarget.INSTANT), CUSTOM_TIMEZONE_SOURCE_FILES, "orm74");
        assertTrue(result.skipped().isEmpty());
        assertTrue(result.text().contains("java.time.Instant stamp"), result.text());
        assertFalse(result.text().contains("getTimeZone()"), result.text());
    }

    @Test
    void configuredZoneCanOverrideCustomCalendarTimezone() {
        var recipe = new MigrateTemporalAnnotation(MigrateTemporalAnnotation.TimestampTarget.ZONED,
                false, null, "Europe/Paris", null);
        var result = ApiValidation.run(recipe, CUSTOM_TIMEZONE_SOURCE_FILES, "orm74");
        assertTrue(result.skipped().isEmpty());
        assertTrue(result.text().contains("java.time.ZonedDateTime stamp"), result.text());
        assertTrue(result.text().contains("ZoneId.of(\"Europe/Paris\")"), result.text());
        assertFalse(result.text().contains("getTimeZone()"), result.text());
    }

    private static final String CALENDAR_SOURCE_PATH = "fixture/temporal/temporalconfiguration/calendar_source/Example.java";
    private static final Map<String, String> CALENDAR_SOURCE_FILES = MigrationSources.configured().files(CALENDAR_SOURCE_PATH);
    private static final String CALENDAR_SOURCE = CALENDAR_SOURCE_FILES.get(CALENDAR_SOURCE_PATH);

    @Test
    void calendarTimezoneIsHonoredByDefaultWithoutFallback() {
        var recipe = new MigrateTemporalAnnotation(MigrateTemporalAnnotation.TimestampTarget.ZONED, null, null, null, null);
        var result = ApiValidation.run(recipe, CALENDAR_SOURCE_FILES, "orm74");
        assertTrue(result.skipped().isEmpty());
        assertTrue(result.text().contains("java.time.ZonedDateTime value"), result.text());
        assertTrue(result.text().contains("getTimeZone().toZoneId()"), result.text());
    }

    @Test
    void ignoringCalendarTimezoneRequiresFallback() {
        var recipe = new MigrateTemporalAnnotation(MigrateTemporalAnnotation.TimestampTarget.ZONED, false, null, null, null);
        var result = ApiValidation.run(recipe, CALENDAR_SOURCE_FILES, "orm74");
        assertEquals(CALENDAR_SOURCE, result.text());
        assertEquals(1, result.skipped().size());
        assertEquals("TEMPORAL_TIMEZONE_REQUIRED", result.skipped().getFirst().getReasonCode());
    }

    @ParameterizedTest(name = "declaration and null assignment need no zone: {0}")
    @EnumSource(MigrateTemporalAnnotation.TimestampTarget.class)
    void declarationOnlyTargetsAndNullAssignmentsNeedNoZone(MigrateTemporalAnnotation.TimestampTarget target) {
        var sourceFiles = MigrationSources.configured().files("fixture/temporal/temporalconfiguration/declarationonlytargetsandnullassignmentsneednozone/Example.java");
        String source = sourceFiles.get("fixture/temporal/temporalconfiguration/declarationonlytargetsandnullassignmentsneednozone/Example.java");
        var recipe = new MigrateTemporalAnnotation(target, false, null, null, null);
        var result = ApiValidation.run(recipe, sourceFiles, "orm74");
        assertTrue(result.skipped().isEmpty());
        assertFalse(result.text().contains("@Temporal"), result.text());
        assertTrue(result.text().contains("value = null;"), result.text());
    }

    @Test
    void invalidOffsetFailsValidation() {
        assertFalse(new MigrateTemporalAnnotation(null, null, "25:00", null, null).validate().isValid());
    }

    @Test
    void invalidZoneIdFailsValidation() {
        assertFalse(new MigrateTemporalAnnotation(null, null, null, "Not/AZone", null).validate().isValid());
    }

    @Test
    void yamlBindsLocalTargetAndOffsetPolicy() {
        String yaml = """
                type: specs.openrewrite.org/v1beta/recipe
                name: example.Temporal
                displayName: Temporal
                description: Test temporal configuration.
                recipeList:
                  - org.hibernate.migration.recipes.temporal.MigrateTemporalAnnotation:
                      timestampTarget: LOCAL
                      honorCalendarTimeZone: false
                      offset: "+02:00"
                      localTimezoneSource: OFFSET
                """;
        var loader = new YamlResourceLoader(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)),
                URI.create("memory:temporal.yml"), new Properties());
        var recipe = Environment.builder().load(loader).build().activateRecipes("example.Temporal");
        var result = ApiValidation.run(recipe, MigrationSources.configured().files("fixture/temporal/temporalconfiguration/yamlbindslocaltargetandoffsetpolicy/Example.java"), "orm74");
        assertTrue(result.text().contains("java.time.LocalDateTime"), result.text() + recipe.validate().failures());
        assertTrue(result.text().contains("ZoneOffset.of(\"+02:00\")"));
    }
    private MigrateTemporalAnnotation configured(MigrateTemporalAnnotation.TimestampTarget target) {
        return new MigrateTemporalAnnotation(target, true, "+02:00", "Europe/Paris",
                MigrateTemporalAnnotation.LocalTimezoneSource.ZONE_ID);
    }

}
