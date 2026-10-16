package org.hibernate.migration.recipes.jpa4;

import org.hibernate.migration.testing.ApiValidation;
import org.hibernate.migration.testing.MigrationSources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import org.hibernate.migration.recipes.table.SkippedMigrations;
import org.openrewrite.*;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import static org.junit.jupiter.api.Assertions.*;

/// Checks composite selection and reporting, including deliberately stripped attribution.
/// @author Steve Ebersole
class CompositeMigrationTest {
    @ParameterizedTest
    @ValueSource(strings = {"orm80", "orm80-required"})
    void compositeKeepsTemporalAndUnrelatedCode(String selection) {
        String path = "fixture/jpa4/compositemigration/compositeskeeptemporalandunrelatedcode/Example.java";
        String unrelatedPath = "fixture/jpa4/compositemigration/compositeskeeptemporalandunrelatedcode/unrelated/Other.java";
        var sources = MigrationSources.configured().files(path, unrelatedPath);
        String source = sources.get(path);
        String temporal = source.substring(source.indexOf("@Temporal(DATE)"), source.indexOf("@MapKey"));
        String unrelated = sources.get(unrelatedPath);
        String api = "orm74";
        var result = ApiValidation.run(ApiValidation.composite("org.hibernate.migration.recipes." + selection), sources, api);
        assertTrue(result.files().get(path).contains(temporal));
        assertEquals(unrelated, result.files().get(unrelatedPath));
        assertTrue(result.files().get(path).contains("@NamedStatement"));
        if (selection.endsWith("-required")) {
            assertTrue(result.files().get(path).contains("em.getDelegate()"));
            assertTrue(result.files().get(path).contains("@MapKey(name = \"id\")"));
        }
        else {
            assertTrue(result.files().get(path).contains("unwrap(java.lang.Object.class)"), api);
            assertTrue(result.files().get(path).contains("@MapKey(\"id\")"), api);
        }
        assertTrue(result.skipped().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"orm80", "orm80-required"})
    void missingAttributionNeverEditsAndReportsAgainOnNewExecution(String selection) {
        String input = """
                import jakarta.persistence.MapKey;
                import jakarta.persistence.EntityManager;
                @jakarta.persistence.NamedQuery(name="x", query="delete from Thing")
                class Example {
                    @MapKey(name="x") Object map;
                    Object delegate(EntityManager em) { return em.getDelegate(); }
                }
                """;
        var ctx = new InMemoryExecutionContext(t -> fail(t));
        SourceFile parsed = ApiValidation.parser("orm74").build().parse(ctx, input).findFirst().orElseThrow();
        // Deliberately remove attribution; raw execution tests reporting rather than compilation.
        SourceFile stripped = (SourceFile) new JavaIsoVisitor<Integer>() {
            @Override public J.Annotation visitAnnotation(J.Annotation a, Integer p) { return super.visitAnnotation(a, p).withType(null); }
            @Override public J.MethodInvocation visitMethodInvocation(J.MethodInvocation m, Integer p) { return super.visitMethodInvocation(m, p).withMethodType(null).withName(m.getName().withType(null)); }
        }.visit(parsed, 0);
        var recipe = ApiValidation.composite("org.hibernate.migration.recipes." + selection);
        for (int attempt = 0; attempt < 2; attempt++) {
            var run = recipe.run(new InMemoryLargeSourceSet(List.of(stripped)), new InMemoryExecutionContext(t -> fail(t)));
            assertEquals(0, run.getChangeset().size());
            var rows = run.getDataTableRows(SkippedMigrations.class);
            List<Integer> expectedLines = selection.endsWith("-required") ? List.of(3) : List.of(3, 5, 6);
            assertEquals(expectedLines.size(), rows.size());
            assertEquals(expectedLines, rows.stream().map(row -> row.getLine()).sorted().toList(),
                    "Each original candidate is reported on execution " + attempt);
            assertTrue(rows.stream().allMatch(r -> r.getReasonCode().equals("MISSING_TYPE_ATTRIBUTION")));
        }
    }

    @Test
    void stagedJavaUpgradeMatchesComprehensiveAndCompilesWithBothApis() {
        var sources = MigrationSources.configured().files(
                "fixture/jpa4/compositemigration/compositeskeeptemporalandunrelatedcode/Example.java",
                "fixture/optimistic/RuntimeEntity.java",
                "fixture/queryflush/qualified/Example.java",
                "fixture/queryhints/qualified/Example.java",
                "fixture/findmultiple/calls/Example.java",
                "fixture/sqlverification/Imported.java");
        var required = ApiValidation.run(ApiValidation.composite("org.hibernate.migration.recipes.orm80-required"), sources, "orm74");
        assertTrue(required.skipped().isEmpty());
        assertEquals(sources.get("fixture/optimistic/RuntimeEntity.java"),
                required.files().get("fixture/optimistic/RuntimeEntity.java"));
        assertTrue(required.files().get("fixture/queryflush/qualified/Example.java").contains("jakarta.persistence.QueryFlushMode"));
        assertFalse(required.files().get("fixture/queryhints/qualified/Example.java").contains("org.hibernate.jpa.QueryHints"));
        assertTrue(required.files().get("fixture/findmultiple/calls/Example.java").contains("org.hibernate.FindMultipleOption"));
        assertTrue(required.files().get("fixture/sqlverification/Imported.java").contains("verify ="));
        var full = ApiValidation.run(ApiValidation.composite("org.hibernate.migration.recipes.orm80"), sources, "orm74");
        var staged = ApiValidation.run(ApiValidation.composite("org.hibernate.migration.recipes.orm80"), required.files(), "orm80");
        assertEquals(full.files(), staged.files());
        assertTrue(full.skipped().isEmpty());
        assertTrue(staged.skipped().isEmpty());
        assertFalse(full.files().get("fixture/optimistic/RuntimeEntity.java").contains("@OptimisticLock"));
    }

}
