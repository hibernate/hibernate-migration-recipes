package org.hibernate.migration.recipes.temporal;

import org.hibernate.migration.testing.ApiValidation;
import org.hibernate.migration.testing.MigrationSources;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/// Verifies property mappings, accessors, and declaration preservation.
/// @author Steve Ebersole
class TemporalPropertyConversionTest {
    @ParameterizedTest
    @EnumSource(MigrateTemporalAnnotation.TimestampTarget.class)
    void propertyAccessWithCalendar(MigrateTemporalAnnotation.TimestampTarget target) {
        var result = ApiValidation.run(configured(target), MigrationSources.configured().files("fixture/temporal/temporalpropertyconversion/propertyaccesswithcalendar/Example.java"), "orm74");
        assertTrue(result.skipped().isEmpty(), result.skipped().toString());
        assertTrue(result.text().contains("retain precision"));
        assertFalse(result.text().contains("@Temporal"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"interfacecontract", "superclasscontract"})
    void accessorContractsBlockConversion(String scenario) {
        var sourceFiles = MigrationSources.configured().scenario("fixture/temporal/temporalpropertyconversion/accessorcontractsblockconversion/" + scenario);
        String source = sourceFiles.values().iterator().next();
        var result = ApiValidation.run(new MigrateTemporalAnnotation(), sourceFiles, "orm74");
        assertEquals(source, result.text());
        assertEquals(1, result.skipped().size());
    }

    @Test void overrideOfConvertedBaseAndOverloadBlockConversion() {
        var sourceFiles = MigrationSources.configured().files("fixture/temporal/temporalpropertyconversion/overrideofconvertedbaseandoverloadblockconversion/Example.java");
        String source = sourceFiles.get("fixture/temporal/temporalpropertyconversion/overrideofconvertedbaseandoverloadblockconversion/Example.java");
        var result = ApiValidation.run(new MigrateTemporalAnnotation(), sourceFiles, "orm74");
        assertEquals(source, result.text());
        assertEquals(2, result.skipped().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"sideeffectsetter", "fluentsetter", "copyinggetter", "dategetter", "convertedgetter"})
    void unsupportedAccessorShapesAndMappings(String scenario) {
        var sourceFiles = MigrationSources.configured().scenario("fixture/temporal/temporalpropertyconversion/unsupportedaccessorshapesandmappings/" + scenario);
        String source = sourceFiles.values().iterator().next();
        var result = ApiValidation.run(new MigrateTemporalAnnotation(), sourceFiles, "orm74");
        assertEquals(source, result.text());
        assertFalse(result.skipped().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DATE", "TIME"})
    void localTemporalAccessors(String precision) {
        var sourceFiles = MigrationSources.configured().scenario("fixture/temporal/temporalpropertyconversion/localtemporalaccessors/" + precision.toLowerCase(java.util.Locale.ROOT));
        String source = sourceFiles.values().iterator().next();
        var result = ApiValidation.run(configured(MigrateTemporalAnnotation.TimestampTarget.INSTANT), sourceFiles, "orm74");
        assertTrue(result.skipped().isEmpty(), result.skipped().toString());
        assertTrue(result.text().contains(precision.equals("DATE") ? "java.time.LocalDate getValue" : "java.time.LocalTime getValue"));
        int getterStart = source.indexOf("\tjava.util.Date getValue()");
        int setterStart = source.indexOf("\tvoid setValue(");
        int callerStart = source.indexOf("\tvoid use(");
        assertTrue(getterStart >= 0 && setterStart > getterStart && callerStart > setterStart);
        String getter = source.substring(getterStart, setterStart);
        String setter = source.substring(setterStart, callerStart);
        String reversed = source.replace(getter + setter, setter + getter);
        assertNotEquals(source, reversed);
        assertTrue(ApiValidation.run(configured(MigrateTemporalAnnotation.TimestampTarget.INSTANT), reversed, "orm74").skipped().isEmpty());
    }

    @Test
    void bareMixedFieldsNeedNoTimezone() {
        String api = "orm74";
        var sourceFiles = MigrationSources.configured().files("fixture/temporal/temporalpropertyconversion/baremixedfieldsneednotimezone/Example.java");
        String source = sourceFiles.get("fixture/temporal/temporalpropertyconversion/baremixedfieldsneednotimezone/Example.java");
        var result = ApiValidation.run(new MigrateTemporalAnnotation(), sourceFiles, api);
        assertTrue(result.skipped().isEmpty());
        assertTrue(result.text().contains("java.time.LocalDate day"));
        assertTrue(result.text().contains("java.time.LocalTime time"));
        assertTrue(result.text().contains("java.time.Instant stamp"));
        assertTrue(result.text().contains("java.time.LocalDate calendarDay"));
        assertTrue(result.text().contains("java.time.LocalTime calendarTime"));
        assertTrue(result.text().contains("java.time.Instant calendarStamp"));
        assertTrue(result.text().contains("Date unrelated = new Date();"));
        assertTrue(result.text().contains("Date getUnrelated() {\n\t\treturn unrelated;\n\t}"));
        assertFalse(result.text().contains("@Temporal"));
    }

    @ParameterizedTest
    @EnumSource(MigrateTemporalAnnotation.TimestampTarget.class)
    void initializersAndAssignments(MigrateTemporalAnnotation.TimestampTarget target) {
        var sourceFiles = MigrationSources.configured().files("fixture/temporal/temporalpropertyconversion/initializersandassignments/Example.java");
        String source = sourceFiles.get("fixture/temporal/temporalpropertyconversion/initializersandassignments/Example.java");
        var result = ApiValidation.run(configured(target), sourceFiles, "orm74");
        assertTrue(result.skipped().isEmpty());
        assertFalse(result.text().contains("@Temporal"));
        assertTrue(result.text().contains("== null ? null"));
        if (target != MigrateTemporalAnnotation.TimestampTarget.INSTANT) {
            assertTrue(result.text().contains("getTimeZone().toZoneId()"));
        }
    }

    @Test void missingZoneAndUnsupportedReadsBlockWholeAttribute() {
        var sourceFiles = MigrationSources.configured().files("fixture/temporal/temporalpropertyconversion/missingzoneandunsupportedreadsblockwholeattribute/Example.java");
        String source = sourceFiles.get("fixture/temporal/temporalpropertyconversion/missingzoneandunsupportedreadsblockwholeattribute/Example.java");
        var result = ApiValidation.run(new MigrateTemporalAnnotation(), sourceFiles, "orm74");
        assertEquals(source, result.text());
        assertEquals(Set.of("TEMPORAL_TIMEZONE_REQUIRED", "UNSUPPORTED_TEMPORAL_ACCESSOR"),
                new HashSet<>(result.skipped().stream().map(r -> r.getReasonCode()).toList()));
    }

    @Test void otherAnnotationsCommentsNullAndQualifiedSyntax() {
        var result = ApiValidation.run(new MigrateTemporalAnnotation(), MigrationSources.configured().files("fixture/temporal/temporalpropertyconversion/otherannotationscommentsnullandqualifiedsyntax/Example.java"), "orm74");
        assertTrue(result.skipped().isEmpty());
        assertTrue(result.text().contains("@Basic"));
        assertTrue(result.text().contains("@Column(name = \"CREATED\")"));
        assertTrue(result.text().contains("/* keep */"));
        assertTrue(result.text().contains("/* precision */"));
        assertTrue(result.text().contains("java.time.Instant created = null"));
    }

    @Test void unsupportedFormsRemainUnchanged() {
        var sourceFiles = MigrationSources.configured().files("fixture/temporal/temporalpropertyconversion/unsupportedformsremainunchanged/Example.java");
        String source = sourceFiles.get("fixture/temporal/temporalpropertyconversion/unsupportedformsremainunchanged/Example.java");
        var result = ApiValidation.run(configured(MigrateTemporalAnnotation.TimestampTarget.LOCAL), sourceFiles, "orm74");
        assertEquals(source, result.text());
        assertEquals(6, result.skipped().size());
    }

    @Test void unrelatedLocalTypesAndNameConflictsRemainUnchanged() {
        var sourceFiles = MigrationSources.configured().files("fixture/temporal/temporalpropertyconversion/unrelatedlocaltypesandnameconflictsremainunchanged/Example.java");
        String source = sourceFiles.get("fixture/temporal/temporalpropertyconversion/unrelatedlocaltypesandnameconflictsremainunchanged/Example.java");
        var result = ApiValidation.run(new MigrateTemporalAnnotation(), sourceFiles, "orm74");
        assertEquals(source, result.text());
        assertEquals("NAME_RESOLUTION_CONFLICT", result.skipped().getFirst().getReasonCode());
        var noOpFiles = MigrationSources.configured().files("fixture/temporal/temporalpropertyconversion/unrelatednoop/Example.java");
        String noOp = noOpFiles.get("fixture/temporal/temporalpropertyconversion/unrelatednoop/Example.java");
        assertEquals(noOp, ApiValidation.run(new MigrateTemporalAnnotation(), noOpFiles, "orm74").text());
    }

    private MigrateTemporalAnnotation configured(MigrateTemporalAnnotation.TimestampTarget target) {
        return new MigrateTemporalAnnotation(target, true, "+02:00", "Europe/Paris",
                MigrateTemporalAnnotation.LocalTimezoneSource.ZONE_ID);
    }

}
