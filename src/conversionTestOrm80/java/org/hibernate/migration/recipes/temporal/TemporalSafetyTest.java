/* SPDX-License-Identifier: Apache-2.0 */
package org.hibernate.migration.recipes.temporal;

import org.hibernate.migration.testing.ApiValidation;
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
        var sources = Map.of("p/java.java", "package p; public class java {}", "Example.java", """
                import p.java;
                import java.util.Date;
                import jakarta.persistence.*;
                class Example {
                    @Temporal(TemporalType.TIMESTAMP) Date created;
                }
                """);
        var outcome = ApiValidation.run(new MigrateTemporalAnnotation(), sources, ApiValidation.environments().context("orm74"));
        assertEquals(sources, outcome.files());
        assertEquals("NAME_RESOLUTION_CONFLICT", outcome.skipped().getFirst().getReasonCode());
    }

    @Test void newSignatureMustNotOverrideInheritedFinalMethod() {
        String source = """
                import jakarta.persistence.*;
                class Parent {
                    public final void setCreated(java.time.Instant input) {}
                }
                class Example extends Parent {
                    @Temporal(TemporalType.TIMESTAMP) java.util.Date created;
                    public void setCreated(java.util.Date input) { created = input; }
                }
                """;
        var outcome = ApiValidation.run(new MigrateTemporalAnnotation(), source, "orm74");
        assertEquals(source, outcome.text());
        assertEquals("UNSUPPORTED_TEMPORAL_HIERARCHY", outcome.skipped().getFirst().getReasonCode());
    }
    @ParameterizedTest
    @ValueSource(strings = {"import p.*;", "import p.Parent.java;", "import p.Parent.*;", "import static p.Parent.java;", "import static p.Parent.*;"})
    void importedTypesNamedJava(String imported) {
        var sources = Map.of(
                "p/java.java", "package p; public class java {}",
                "p/Parent.java", "package p; public class Parent { public static class java {} }",
                "Example.java", imported + """
                import java.util.Date;
                import jakarta.persistence.*;
                class Example { @Temporal(TemporalType.TIMESTAMP) Date created; }
                """);
        var result = ApiValidation.run(new MigrateTemporalAnnotation(), sources, ApiValidation.environments().context("orm74"));
        assertEquals(sources, result.files());
        assertEquals("NAME_RESOLUTION_CONFLICT", result.skipped().getFirst().getReasonCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"protected int java;", "public static class java {}"})
    void inheritedJavaName(String member) {
        var sources = Map.of("p/Parent.java", "package p; public class Parent { " + member + " }", "Example.java", """
                import java.util.Date;
                import jakarta.persistence.*;
                class Example extends p.Parent {
                    @Temporal(TemporalType.TIMESTAMP) Date created = new Date();
                }
                """);
        var result = ApiValidation.run(new MigrateTemporalAnnotation(), sources, ApiValidation.environments().context("orm74"));
        assertEquals(sources, result.files());
        assertEquals("NAME_RESOLUTION_CONFLICT", result.skipped().getFirst().getReasonCode());
    }

    @Test void samePackageJavaName() {
        var sources = Map.of("p/java.java", "package p; public class java {}", "p/Example.java", """
                package p;
                import java.util.Date;
                import jakarta.persistence.*;
                class Example { @Temporal(TemporalType.TIMESTAMP) Date created; }
                """);
        var result = ApiValidation.run(new MigrateTemporalAnnotation(), sources, ApiValidation.environments().context("orm74"));
        assertEquals(sources, result.files());
        assertEquals("NAME_RESOLUTION_CONFLICT", result.skipped().getFirst().getReasonCode());
    }

    @Test void callerImportBlocksPropertyAtomically() {
        var sources = Map.of("p/java.java", "package p; public class java {}", "Entity.java", """
                import jakarta.persistence.*;
                class Entity {
                    @Temporal(TemporalType.TIMESTAMP) java.util.Date created;
                    void setCreated(java.util.Date input) { created = input; }
                }
                """, "Client.java", """
                import p.java;
                import java.util.Date;
                class Client { void use(Entity e) { e.setCreated(new Date()); } }
                """);
        var result = ApiValidation.run(new MigrateTemporalAnnotation(), sources, ApiValidation.environments().context("orm74"));
        assertEquals(sources, result.files());
        assertEquals("NAME_RESOLUTION_CONFLICT", result.skipped().getFirst().getReasonCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "class Parent { public void setCreated(java.time.Instant input) {} } class Example extends Parent",
            "class Parent { public static void setCreated(java.time.Instant input) {} } class Example extends Parent",
            "interface Parent { default void setCreated(java.time.Instant input) {} } class Example implements Parent",
            "class Parent<T> { public void setCreated(T input) {} } class Example extends Parent<java.time.Instant>"
    })
    void inheritedOverloadMustNotBecomeAnOverride(String declarations) {
        String source = "import jakarta.persistence.*;\n" + declarations + """
                 {
                    @Temporal(TemporalType.TIMESTAMP) java.util.Date created;
                    public void setCreated(java.util.Date input) { created = input; }
                }
                """;
        var result = ApiValidation.run(new MigrateTemporalAnnotation(), source, "orm74");
        assertEquals(source, result.text());
        assertEquals("UNSUPPORTED_TEMPORAL_HIERARCHY", result.skipped().getFirst().getReasonCode());
    }

    @Test void descendantOverloadBlocksBaseProperty() {
        String source = """
                import jakarta.persistence.*;
                class Example {
                    @Temporal(TemporalType.TIMESTAMP) java.util.Date created;
                    public final void setCreated(java.util.Date input) { created = input; }
                }
                class Child extends Example { public void setCreated(java.time.Instant input) {} }
                """;
        var result = ApiValidation.run(new MigrateTemporalAnnotation(), source, "orm74");
        assertEquals(source, result.text());
        assertEquals("UNSUPPORTED_TEMPORAL_HIERARCHY", result.skipped().getFirst().getReasonCode());
    }

    @Test void privateAncestorMethodDoesNotBlockConversion() {
        var result = ApiValidation.run(new MigrateTemporalAnnotation(), """
                import jakarta.persistence.*;
                class Parent { private void setCreated(java.time.Instant input) {} }
                class Example extends Parent {
                    @Temporal(TemporalType.TIMESTAMP) java.util.Date created;
                    public void setCreated(java.util.Date input) { created = input; }
                }
                """, "orm74");
        assertTrue(result.skipped().isEmpty());
        assertTrue(result.text().contains("public void setCreated(java.time.Instant input)"));
    }

    @Test void importedNameConflict() {
        var sources = Map.of(
                "p/java.java", "package p; public class java {}",
                "Example.java", """
                        import p.java;
                        import java.util.Date;
                        import jakarta.persistence.*;
                        class Example { @Temporal(TemporalType.TIMESTAMP) Date created; }
                        """);
        var recipe = new MigrateTemporalAnnotation();
        var result = ApiValidation.run(recipe, sources, "orm74");
        assertEquals(sources, result.files());
        assertEquals(1, result.skipped().size());
        assertDiagnostic(result.skipped().getFirst(), 4, 17, "NAME_RESOLUTION_CONFLICT");
        var reversed = new LinkedHashMap<String, String>();
        List.copyOf(sources.entrySet()).reversed()
                .forEach(entry -> reversed.put(entry.getKey(), entry.getValue()));
        var reversedResult = ApiValidation.run(recipe, reversed, "orm74");
        assertEquals(result.files(), reversedResult.files());
        assertEquals(1, reversedResult.skipped().size());
        assertDiagnostic(reversedResult.skipped().getFirst(), 4, 17, "NAME_RESOLUTION_CONFLICT");
        assertEquals(result.skipped().getFirst().getMessage(), reversedResult.skipped().getFirst().getMessage());
    }

    private void assertDiagnostic(SkippedMigrations.Row row, int line, int column, String reason) {
        assertNotNull(row);
        assertEquals(MigrateTemporalAnnotation.class.getName(), row.getRecipe());
        assertEquals("Example.java", row.getSourcePath());
        assertEquals("Temporal", row.getSubject());
        assertEquals(line, row.getLine());
        assertEquals(column, row.getColumn());
        assertEquals(reason, row.getReasonCode());
        assertFalse(row.getMessage().isBlank());
    }
}
