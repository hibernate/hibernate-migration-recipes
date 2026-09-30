package org.hibernate.migration.recipes.temporal;

import org.hibernate.migration.testing.ApiValidation;

import java.util.Map;
import java.util.List;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.hibernate.migration.recipes.table.SkippedMigrations;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// Checks rejection precedence and original locations, including deliberately missing attribution.
/// @author Steve Ebersole
class TemporalDiagnosticsTest {
    @Test
    void rejectedPropertiesAndDiagnosticPrecedence() {
        var sources = Map.of("Example.java", """
                import jakarta.persistence.*;
                class Parent { public final void setCreated(java.time.Instant value) {} }
                class Example extends Parent {
                    @Temporal(TemporalType.DATE) java.util.Date missing = new java.util.Date();
                    @Temporal(TemporalType.TIMESTAMP) java.util.Date created;
                    public void setCreated(java.util.Date value) { created = value; }
                    @Temporal(TemporalType.TIMESTAMP) java.util.Date first;
                    @Temporal(TemporalType.TIMESTAMP) java.util.Date second;
                    java.util.Date getFirst() { return first; }
                    void setSecond(java.util.Date value) { second = value; }
                    void use() { setSecond(getFirst()); getFirst().setTime(1); }
                    @Temporal(TemporalType.DATE) java.util.Date getComputed() { return new java.util.Date(); }
                }
                """);
        var result = ApiValidation.run(new MigrateTemporalAnnotation(), sources, "orm74");
        assertEquals(sources, result.files(), "Rejected and connected properties remain unchanged");
        assertEquals(5, result.skipped().size());
        var byLine = result.skipped().stream().collect(Collectors.toMap(SkippedMigrations.Row::getLine, row -> row));
        // Assert the first applicable blocker, including propagation to the connected property.
        assertDiagnostic(byLine.get(4), 4, 5, "TEMPORAL_TIMEZONE_REQUIRED");
        assertDiagnostic(byLine.get(5), 5, 5, "UNSUPPORTED_TEMPORAL_HIERARCHY");
        assertDiagnostic(byLine.get(7), 7, 5, "UNSUPPORTED_TEMPORAL_USAGE");
        assertTrue(byLine.get(7).getMessage().contains("Example.java:11:41"), "Consumer coordinates refer to original input");
        assertDiagnostic(byLine.get(8), 8, 5, "CONNECTED_TEMPORAL_PROPERTY_BLOCKED");
        assertDiagnostic(byLine.get(12), 12, 5, "UNSUPPORTED_TEMPORAL_ATTRIBUTE");
    }

    @Test
    void unresolvedExplicitAnnotationIsReportedWithoutEdits() {
        String source = """
                import jakarta.persistence.Temporal;
                import jakarta.persistence.TemporalType;
                class Example {
                    @Temporal(TemporalType.TIMESTAMP) java.util.Date created;
                }
                """;
        var ctx = new InMemoryExecutionContext(error -> { throw new AssertionError(error); });
        var input = ApiValidation.parser("orm74").build().parse(ctx, source).findFirst().orElseThrow();
        var unattributed = (J.CompilationUnit) new JavaIsoVisitor<Void>() {
            @Override public J.Annotation visitAnnotation(J.Annotation a, Void unused) {
                return a.withAnnotationType(a.getAnnotationType().withType(null));
            }
        }.visit(input, null);
        var run = new MigrateTemporalAnnotation().run(new InMemoryLargeSourceSet(List.of(unattributed)), ctx);
        assertEquals(0, run.getChangeset().size());
        List<SkippedMigrations.Row> rows = run.getDataTableRows(SkippedMigrations.class);
        assertEquals(1, rows.size());
        var row = rows.getFirst();
        assertEquals("MISSING_TYPE_ATTRIBUTION", row.getReasonCode());
        assertEquals("jakarta.persistence.Temporal", row.getSubject());
        assertEquals("Resolve the source JPA API before migrating this annotation.", row.getMessage());
        assertEquals(4, row.getLine());
        assertEquals(5, row.getColumn());
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
