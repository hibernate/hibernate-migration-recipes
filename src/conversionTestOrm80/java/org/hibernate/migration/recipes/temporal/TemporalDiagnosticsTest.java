package org.hibernate.migration.recipes.temporal;

import org.hibernate.migration.testing.ApiValidation;
import org.hibernate.migration.testing.MigrationSources;

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
        var sources = MigrationSources.configured().files("fixture/temporal/temporaldiagnostics/rejectedpropertiesanddiagnosticprecedence/Example.java");
        var result = ApiValidation.run(new MigrateTemporalAnnotation(), sources, "orm74");
        assertEquals(sources, result.files(), "Rejected and connected properties remain unchanged");
        assertEquals(5, result.skipped().size());
        var byLine = result.skipped().stream().collect(Collectors.toMap(SkippedMigrations.Row::getLine, row -> row));
        // Assert the first applicable blocker, including propagation to the connected property.
        assertDiagnostic(byLine.get(11), 11, 2, "TEMPORAL_TIMEZONE_REQUIRED");
        assertDiagnostic(byLine.get(13), 13, 2, "UNSUPPORTED_TEMPORAL_HIERARCHY");
        assertDiagnostic(byLine.get(20), 20, 2, "UNSUPPORTED_TEMPORAL_USAGE");
        assertTrue(byLine.get(20).getMessage().contains("fixture/temporal/temporaldiagnostics/rejectedpropertiesanddiagnosticprecedence/Example.java:35:3"), "Consumer coordinates refer to original input");
        assertDiagnostic(byLine.get(22), 22, 2, "CONNECTED_TEMPORAL_PROPERTY_BLOCKED");
        assertDiagnostic(byLine.get(38), 38, 2, "UNSUPPORTED_TEMPORAL_ATTRIBUTE");
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
        assertEquals("fixture/temporal/temporaldiagnostics/rejectedpropertiesanddiagnosticprecedence/Example.java", row.getSourcePath());
        assertEquals("Temporal", row.getSubject());
        assertEquals(line, row.getLine());
        assertEquals(column, row.getColumn());
        assertEquals(reason, row.getReasonCode());
        assertFalse(row.getMessage().isBlank());
    }
}
