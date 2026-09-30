/* SPDX-License-Identifier: Apache-2.0 */
package org.hibernate.migration.recipes.temporal;

import org.hibernate.migration.testing.ApiValidation;

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
        sources.put("Client.java", """
                class Client {
                    void use(Entity e, java.util.Date legacy) {
                        e.setCreated(legacy);
                        java.util.Date first = e.getCreated();
                        final java.util.Date alias = first;
                        if (alias != null) e.setCreated(alias);
                        e.getCreated();
                    }
                }
                """);
        sources.put("Entity.java", """
                import jakarta.persistence.*;
                class Entity {
                    @Temporal(TemporalType.TIMESTAMP) private java.util.Date created;
                    public java.util.Date getCreated() { return this.created; }
                    public void setCreated(java.util.Date input) { this.created = input; }
                }
                """);
        var result = ApiValidation.run(new MigrateTemporalAnnotation(), sources, ApiValidation.environments().context("orm74"));
        assertTrue(result.skipped().isEmpty(), result.skipped().toString());
        assertTrue(result.files().get("Entity.java").contains("java.time.Instant getCreated"));
        assertTrue(result.files().get("Client.java").contains("java.time.Instant first"));
        var reversed = new LinkedHashMap<String, String>();
        reversed.put("Entity.java", sources.get("Entity.java"));
        reversed.put("Client.java", sources.get("Client.java"));
        assertEquals(result.files(), ApiValidation.run(new MigrateTemporalAnnotation(), reversed, ApiValidation.environments().context("orm74")).files());
    }

    @Test void getterAnnotationAndPropertyTransfers() {
        String source = """
                import jakarta.persistence.*;
                class Example {
                    java.util.Date first;
                    @Temporal(TemporalType.TIMESTAMP) java.util.Date second;
                    @Temporal(TemporalType.TIMESTAMP)
                    public java.util.Date getFirst() { return first; }
                    public void setFirst(java.util.Date first) { this.first = first; }
                    public java.util.Date getSecond() { return second; }
                    public void setSecond(java.util.Date second) { this.second = second; }
                    void transfer(Example e) { setFirst(e.getSecond()); e.setSecond(getFirst()); }
                }
                """;
        var result = ApiValidation.run(new MigrateTemporalAnnotation(), source, "orm74");
        assertTrue(result.skipped().isEmpty(), result.skipped().toString());
        assertFalse(result.text().contains("@Temporal"));
        assertFalse(result.text().contains("Function<"));
    }

    @Test void unsupportedClientBlocksConnectedProperties() {
        String source = """
                import jakarta.persistence.*;
                class Example {
                    @Temporal(TemporalType.TIMESTAMP) java.util.Date first;
                    @Temporal(TemporalType.TIMESTAMP) java.util.Date second;
                    java.util.Date getFirst() { return first; }
                    void setSecond(java.util.Date input) { second = input; }
                    void use() { setSecond(getFirst()); getFirst().setTime(1); }
                }
                """;
        var result = ApiValidation.run(new MigrateTemporalAnnotation(), source, "orm74");
        assertEquals(source, result.text());
        assertEquals(2, result.skipped().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "java.util.function.Supplier<java.util.Date> reference = e::getCreated;",
            "java.util.Date local = e.getCreated(); local = new java.util.Date();",
            "java.util.Date local = e.getCreated(); consume(local);",
            "java.util.Date local = e.getCreated(); Runnable r = () -> { if (local != null) {} };",
            "Object local = e.getCreated();",
            "java.util.Date[] local = { e.getCreated() };",
            "e.getCreated().setTime(1);"
    })
    void unsupportedClientsAreAtomic(String use) {
        String source = """
                import jakarta.persistence.*;
                class Example {
                    @Temporal(TemporalType.TIMESTAMP) java.util.Date created;
                    java.util.Date getCreated() { return created; }
                    void setCreated(java.util.Date input) { created = input; }
                    static void consume(Object o) {}
                    void use(Example e) { %s }
                }
                """.formatted(use);
        var result = ApiValidation.run(new MigrateTemporalAnnotation(), source, "orm74");
        assertEquals(source, result.text());
        assertEquals(1, result.skipped().size());
    }

    @Test void incompatibleTransferBlocksOnlyConnectedProperties() {
        var result = ApiValidation.run(configured(MigrateTemporalAnnotation.TimestampTarget.INSTANT), """
                import jakarta.persistence.*;
                class Example {
                    @Temporal(TemporalType.DATE) java.util.Date day;
                    @Temporal(TemporalType.TIMESTAMP) java.util.Date stamp;
                    @Temporal(TemporalType.TIME) java.util.Date unrelated;
                    java.util.Date getDay() { return day; }
                    void setStamp(java.util.Date input) { stamp = input; }
                    void use() { setStamp(getDay()); }
                }
                """, "orm74");
        assertEquals(2, result.skipped().size());
        assertTrue(result.text().contains("@Temporal(TemporalType.DATE) java.util.Date day"));
        assertTrue(result.text().contains("@Temporal(TemporalType.TIMESTAMP) java.util.Date stamp"));
        assertTrue(result.text().contains("java.time.LocalTime unrelated"));
    }

    @Test void blockedCrossFileOrderHasStableDiagnostics() {
        var sources = new LinkedHashMap<String, String>();
        sources.put("Client.java", "class Client { Object use(Entity e) { return e.getCreated(); } }");
        sources.put("Entity.java", """
                import jakarta.persistence.*;
                class Entity {
                    @Temporal(TemporalType.TIMESTAMP) java.util.Date created;
                    java.util.Date getCreated() { return created; }
                }
                """);
        var first = ApiValidation.run(new MigrateTemporalAnnotation(), sources, ApiValidation.environments().context("orm74"));
        var reversed = new LinkedHashMap<String, String>();
        reversed.put("Entity.java", sources.get("Entity.java"));
        reversed.put("Client.java", sources.get("Client.java"));
        var second = ApiValidation.run(new MigrateTemporalAnnotation(), reversed, ApiValidation.environments().context("orm74"));
        assertEquals(sources, first.files());
        assertEquals(first.files(), second.files());
        assertEquals(first.skipped().getFirst().getReasonCode(), second.skipped().getFirst().getReasonCode());
        assertEquals(first.skipped().getFirst().getMessage(), second.skipped().getFirst().getMessage());
        assertTrue(first.skipped().getFirst().getMessage().contains("Client.java"));
    }

    @Test void crossFileWritesAreConvertedAndReadsBlock() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("Example.java", "import jakarta.persistence.*; class Example { @Temporal(TemporalType.TIMESTAMP) java.util.Date value; }");
        sources.put("Writer.java", "class Writer { void write(Example e, java.util.Date d) { e.value = d; } }");
        var result = ApiValidation.run(new MigrateTemporalAnnotation(), sources, "orm74");
        assertTrue(result.skipped().isEmpty());
        assertTrue(result.files().get("Writer.java").contains(".apply(d)"));
        sources.put("Reader.java", "class Reader { java.util.Date read(Example e) { return e.value; } }");
        result = ApiValidation.run(new MigrateTemporalAnnotation(), sources, "orm74");
        assertEquals(sources, result.files());
        assertEquals(1, result.skipped().size());
    }

    @Test void assignmentExpressionsAndMutableCallsBlockWholeField() {
        String source = """
                import jakarta.persistence.*;
                class Example {
                    @Temporal(TemporalType.TIMESTAMP) java.util.Date value = new java.util.Date();
                    Object update() { return value = new java.util.Date(); }
                    @Temporal(TemporalType.DATE) java.util.Calendar day = java.util.Calendar.getInstance();
                    void change() { day.add(java.util.Calendar.DAY_OF_MONTH, 1); }
                }
                """;
        var result = ApiValidation.run(configured(MigrateTemporalAnnotation.TimestampTarget.INSTANT), source, "orm74");
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
        sources.put("Client.java", """
                class Client {
                    void use(Entity e, java.util.Date input) {
                        e.setCreated(input);
                        java.util.Date first = e.getCreated();
                        final java.util.Date alias = first;
                        if (alias != null) e.setCreated(alias);
                    }
                }
                """);
        sources.put("Entity.java", """
                import jakarta.persistence.*;
                import static jakarta.persistence.TemporalType.*;
                class Entity {
                    @Temporal(DATE) java.util.Date day = new java.util.Date(0);
                    @Temporal(TIME) java.util.Calendar time = java.util.Calendar.getInstance();
                    private java.util.Date created;
                    @Temporal(/* precision */ TIMESTAMP)
                    public java.util.Date getCreated() { return created; }
                    public void setCreated(java.util.Date value) { created = value; }
                    @Temporal(TIMESTAMP) java.util.Calendar calendar = java.util.Calendar.getInstance();
                }
                """);
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
        String entity = result.files().get("Entity.java");
        assertFalse(entity.contains("@Temporal"));
        assertTrue(entity.contains("java.time.LocalDate day ="));
        assertTrue(entity.contains("java.time.LocalTime time ="));
        assertTrue(entity.contains("private " + targetType + " created;"));
        assertTrue(entity.contains("public " + targetType + " getCreated()"));
        assertTrue(entity.contains("setCreated(" + targetType + " value)"));
        assertTrue(entity.contains(targetType + " calendar ="));
        assertTrue(entity.contains("/* precision */"));
        String client = result.files().get("Client.java");
        assertTrue(client.contains("java.util.Date input"), "Unrelated caller parameter stays legacy");
        assertTrue(client.contains(".apply(input)"), "Legacy argument is converted at the setter boundary");
        assertTrue(client.contains(targetType + " first = e.getCreated();"));
        assertTrue(client.contains("final " + targetType + " alias = first;"));
        assertTrue(client.contains("if (alias != null) e.setCreated(alias);"), "Converted aliases pass through");
        var reversed = new LinkedHashMap<String, String>();
        reversed.put("Entity.java", sources.get("Entity.java"));
        reversed.put("Client.java", sources.get("Client.java"));
        var reversedResult = ApiValidation.run(recipe, reversed, "orm74");
        assertEquals(result.files(), reversedResult.files());
        assertTrue(reversedResult.skipped().isEmpty());
    }


}
