package org.hibernate.migration.recipes.jpa4;

import org.hibernate.migration.testing.ApiValidation;
import org.hibernate.migration.testing.MigrationSources;
import org.junit.jupiter.api.Test;
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
    @Test
    void compositeKeepsTemporalAndUnrelatedCode() {
        String path = "fixture/jpa4/compositemigration/compositeskeeptemporalandunrelatedcode/Example.java";
        String unrelatedPath = "fixture/jpa4/compositemigration/compositeskeeptemporalandunrelatedcode/unrelated/Other.java";
        var sources = MigrationSources.configured().files(path, unrelatedPath);
        String source = sources.get(path);
        String temporal = source.substring(source.indexOf("@Temporal(DATE)"), source.indexOf("@MapKey"));
        String unrelated = sources.get(unrelatedPath);
        for (String api : List.of("orm74", "jpa30", "jpa31", "jpa32")) {
            var result = ApiValidation.run(ApiValidation.composite("org.hibernate.migration.recipes.orm80"), sources, api);
            assertTrue(result.files().get(path).contains(temporal));
            assertEquals(unrelated, result.files().get(unrelatedPath));
            assertTrue(result.files().get(path).contains("unwrap(java.lang.Object.class)"), api);
            assertTrue(result.files().get(path).contains("@MapKey(\"id\")"), api);
            assertTrue(result.skipped().isEmpty());
        }
    }

    @Test
    void missingAttributionNeverEditsAndReportsAgainOnNewExecution() {
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
        SourceFile parsed = ApiValidation.parser("jpa32").build().parse(ctx, input).findFirst().orElseThrow();
        // Deliberately remove attribution; raw execution tests reporting rather than compilation.
        SourceFile stripped = (SourceFile) new JavaIsoVisitor<Integer>() {
            @Override public J.Annotation visitAnnotation(J.Annotation a, Integer p) { return super.visitAnnotation(a, p).withType(null); }
            @Override public J.MethodInvocation visitMethodInvocation(J.MethodInvocation m, Integer p) { return super.visitMethodInvocation(m, p).withMethodType(null).withName(m.getName().withType(null)); }
        }.visit(parsed, 0);
        var recipe = ApiValidation.composite("org.hibernate.migration.recipes.orm80");
        for (int attempt = 0; attempt < 2; attempt++) {
            var run = recipe.run(new InMemoryLargeSourceSet(List.of(stripped)), new InMemoryExecutionContext(t -> fail(t)));
            assertEquals(0, run.getChangeset().size());
            var rows = run.getDataTableRows(SkippedMigrations.class);
            assertEquals(3, rows.size());
            assertEquals(List.of(3, 5, 6), rows.stream().map(row -> row.getLine()).sorted().toList(),
                    "Each original candidate is reported on execution " + attempt);
            assertTrue(rows.stream().allMatch(r -> r.getReasonCode().equals("MISSING_TYPE_ATTRIBUTION")));
        }
    }

}
