package org.hibernate.migration.recipes.temporal;

import org.hibernate.migration.testing.ApiValidation;

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
        var result = ApiValidation.run(configured(target), """
                import jakarta.persistence.*;
                @Embeddable @Access(AccessType.PROPERTY)
                class Example {
                    private java.util.Calendar stamp;
                    @Temporal(/* retain precision */ TemporalType.TIMESTAMP)
                    @Column(name="stamp")
                    public java.util.Calendar getStamp() { return (this.stamp); }
                    public void setStamp(java.util.Calendar input) { this.stamp = (input); }
                    void use(java.util.Calendar input) { setStamp(input); var value = getStamp(); if (value != null) setStamp(value); }
                }
                """, "orm74");
        assertTrue(result.skipped().isEmpty(), result.skipped().toString());
        assertTrue(result.text().contains("retain precision"));
        assertFalse(result.text().contains("@Temporal"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "interface Contract { java.util.Date getCreated(); } class Example implements Contract",
            "class Parent { public java.util.Date getCreated() { return null; } } class Example extends Parent"
    })
    void accessorContractsBlockConversion(String declaration) {
        String source = "import jakarta.persistence.*;\n" + declaration + """
                 {
                    @Temporal(TemporalType.TIMESTAMP) java.util.Date created;
                    public java.util.Date getCreated() { return created; }
                    public void setCreated(java.util.Date input) { created = input; }
                }
                """;
        var result = ApiValidation.run(new MigrateTemporalAnnotation(), source, "orm74");
        assertEquals(source, result.text());
        assertEquals(1, result.skipped().size());
    }

    @Test void overrideOfConvertedBaseAndOverloadBlockConversion() {
        String source = """
                import jakarta.persistence.*;
                class Example {
                    @Temporal(TemporalType.TIMESTAMP) java.util.Date created;
                    public java.util.Date getCreated() { return created; }
                }
                class Child extends Example {
                    public java.util.Date getCreated() { return new java.util.Date(); }
                }
                class Other {
                    @Temporal(TemporalType.TIMESTAMP) java.util.Date created;
                    public void setCreated(java.util.Date input) { created = input; }
                    public void setCreated(java.time.Instant input) {}
                }
                """;
        var result = ApiValidation.run(new MigrateTemporalAnnotation(), source, "orm74");
        assertEquals(source, result.text());
        assertEquals(2, result.skipped().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "void setCreated(java.util.Date v) { System.nanoTime(); created = v; }",
            "Example setCreated(java.util.Date v) { created = v; return this; }",
            "java.util.Date getCreated() { return new java.util.Date(created.getTime()); }",
            "@Temporal(TemporalType.DATE) java.util.Date getCreated() { return created; }",
            "@Convert java.util.Date getCreated() { return created; }"
    })
    void unsupportedAccessorShapesAndMappings(String accessor) {
        String source = """
                import jakarta.persistence.*;
                class Example {
                    @Temporal(TemporalType.TIMESTAMP) java.util.Date created;
                    %s
                }
                """.formatted(accessor);
        var result = ApiValidation.run(new MigrateTemporalAnnotation(), source, "orm74");
        assertEquals(source, result.text());
        assertFalse(result.skipped().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DATE", "TIME"})
    void localTemporalAccessors(String precision) {
        String source = """
                import jakarta.persistence.*;
                class Example {
                    @Temporal(TemporalType.%s) java.util.Date value;
                    java.util.Date getValue() { return value; }
                    void setValue(java.util.Date input) { value = input; }
                    void use(java.util.Date input) { setValue(input); var v = getValue(); if (v != null) setValue(v); }
                }
                """.formatted(precision);
        var result = ApiValidation.run(configured(MigrateTemporalAnnotation.TimestampTarget.INSTANT), source, "orm74");
        assertTrue(result.skipped().isEmpty(), result.skipped().toString());
        assertTrue(result.text().contains(precision.equals("DATE") ? "java.time.LocalDate getValue" : "java.time.LocalTime getValue"));
        String reversed = source.replace("java.util.Date getValue() { return value; }\n    void setValue(java.util.Date input) { value = input; }",
                "void setValue(java.util.Date input) { value = input; }\n    java.util.Date getValue() { return value; }");
        assertNotEquals(source, reversed);
        assertTrue(ApiValidation.run(configured(MigrateTemporalAnnotation.TimestampTarget.INSTANT), reversed, "orm74").skipped().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"orm74", "jpa30", "jpa31", "jpa32"})
    void bareMixedFieldsNeedNoTimezone(String api) {
        String source = """
                import jakarta.persistence.*;
                import static jakarta.persistence.TemporalType.*;
                import java.util.Date;
                import java.util.Calendar;
                class Example {
                    @Temporal(DATE) Date day;
                    @Temporal(TIME) Date time;
                    @Temporal(value=TIMESTAMP) Date stamp;
                    @Temporal(DATE) Calendar calendarDay;
                    @Temporal(TIME) Calendar calendarTime;
                    @Temporal(TIMESTAMP) Calendar calendarStamp;
                    Date unrelated = new Date();
                    Date getUnrelated() { return unrelated; }
                }
                """;
        var result = ApiValidation.run(new MigrateTemporalAnnotation(), source, api);
        assertTrue(result.skipped().isEmpty());
        assertTrue(result.text().contains("java.time.LocalDate day"));
        assertTrue(result.text().contains("java.time.LocalTime time"));
        assertTrue(result.text().contains("java.time.Instant stamp"));
        assertTrue(result.text().contains("java.time.LocalDate calendarDay"));
        assertTrue(result.text().contains("java.time.LocalTime calendarTime"));
        assertTrue(result.text().contains("java.time.Instant calendarStamp"));
        assertTrue(result.text().contains("Date unrelated = new Date();"));
        assertTrue(result.text().contains("Date getUnrelated() { return unrelated; }"));
        assertFalse(result.text().contains("@Temporal"));
    }

    @ParameterizedTest
    @EnumSource(MigrateTemporalAnnotation.TimestampTarget.class)
    void initializersAndAssignments(MigrateTemporalAnnotation.TimestampTarget target) {
        String source = """
                import jakarta.persistence.*;
                import java.util.Date;
                import java.util.Calendar;
                class Example {
                    @Temporal(TemporalType.TIMESTAMP) Date stamp = new Date(0);
                    @Temporal(TemporalType.TIMESTAMP) Calendar calendar = Calendar.getInstance();
                    void assign(Date value, Calendar other) { stamp = value; calendar = other; }
                }
                """;
        var result = ApiValidation.run(configured(target), source, "orm74");
        assertTrue(result.skipped().isEmpty());
        assertFalse(result.text().contains("@Temporal"));
        assertTrue(result.text().contains("== null ? null"));
        if (target != MigrateTemporalAnnotation.TimestampTarget.INSTANT) {
            assertTrue(result.text().contains("getTimeZone().toZoneId()"));
        }
    }

    @Test void missingZoneAndUnsupportedReadsBlockWholeAttribute() {
        String source = """
                import jakarta.persistence.*;
                import java.util.Date;
                class Example {
                    @Temporal(TemporalType.DATE) Date missing = new Date();
                    @Temporal(TemporalType.TIMESTAMP) Date read = new Date();
                    Date getRead() { return new Date(read.getTime()); }
                    void setRead(Date value) { read = value; }
                }
                """;
        var result = ApiValidation.run(new MigrateTemporalAnnotation(), source, "orm74");
        assertEquals(source, result.text());
        assertEquals(Set.of("TEMPORAL_TIMEZONE_REQUIRED", "UNSUPPORTED_TEMPORAL_ACCESSOR"),
                new HashSet<>(result.skipped().stream().map(r -> r.getReasonCode()).toList()));
    }

    @Test void otherAnnotationsCommentsNullAndQualifiedSyntax() {
        var result = ApiValidation.run(new MigrateTemporalAnnotation(), """
                import jakarta.persistence.Basic;
                import jakarta.persistence.Column;
                class Example {
                    @Basic
                    @Column(name="CREATED")
                    /* keep */ @jakarta.persistence.Temporal(/* precision */ value=jakarta.persistence.TemporalType.TIMESTAMP)
                    java.util.Date created = null;
                }
                """, "orm74");
        assertTrue(result.skipped().isEmpty());
        assertTrue(result.text().contains("@Basic"));
        assertTrue(result.text().contains("@Column(name=\"CREATED\")"));
        assertTrue(result.text().contains("/* keep */"));
        assertTrue(result.text().contains("/* precision */"));
        assertTrue(result.text().contains("java.time.Instant created = null"));
    }

    @Test void unsupportedFormsRemainUnchanged() {
        String source = """
                import jakarta.persistence.*;
                import java.util.*;
                class Example {
                    @Temporal(TemporalType.DATE) String invalid;
                    @Temporal(TemporalType.DATE) Date a, b;
                    @ElementCollection @Temporal(TemporalType.DATE) List<Date> days;
                    @Temporal(TemporalType.DATE) Date getDate() { return null; }
                    @Temporal(TemporalType.TIMESTAMP) Date date = new Date();
                    long epoch() { return date.getTime(); }
                    @Convert(disableConversion=true) @Temporal(TemporalType.DATE) Date custom;
                }
                """;
        var result = ApiValidation.run(configured(MigrateTemporalAnnotation.TimestampTarget.LOCAL), source, "orm74");
        assertEquals(source, result.text());
        assertEquals(6, result.skipped().size());
    }

    @Test void unrelatedLocalTypesAndNameConflictsRemainUnchanged() {
        String source = """
                import jakarta.persistence.*;
                class Example<java> {
                    @Temporal(TemporalType.TIMESTAMP) java.util.Date value;
                }
                """;
        // Use an import because the java type parameter also shadows the original qualified type.
        source = source.replace("import jakarta.persistence.*;", "import jakarta.persistence.*; import java.util.Date;")
                .replace("java.util.Date value", "Date value");
        var result = ApiValidation.run(new MigrateTemporalAnnotation(), source, "orm74");
        assertEquals(source, result.text());
        assertEquals("NAME_RESOLUTION_CONFLICT", result.skipped().getFirst().getReasonCode());
        String noOp = "class Example { java.time.LocalDate date; java.util.Date legacy = new java.util.Date(); }";
        assertEquals(noOp, ApiValidation.run(new MigrateTemporalAnnotation(), noOp, "orm74").text());
    }

    private MigrateTemporalAnnotation configured(MigrateTemporalAnnotation.TimestampTarget target) {
        return new MigrateTemporalAnnotation(target, true, "+02:00", "Europe/Paris",
                MigrateTemporalAnnotation.LocalTimezoneSource.ZONE_ID);
    }

}
