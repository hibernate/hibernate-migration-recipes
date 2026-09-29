/* SPDX-License-Identifier: Apache-2.0 */
package org.hibernate.migration.recipes.jpa4;

import org.hibernate.migration.recipes.table.SkippedMigrations;
import org.junit.jupiter.api.Test;
import org.openrewrite.*;
import org.openrewrite.config.Environment;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/// Separates deliberately invalid or attribution-deficient inputs from compilable fixture coverage.
/// @author Steve Ebersole
class RobustnessTest {
    @Test void sourceRuntimeUsesDeclaredJpa() {
        assertTrue(jakarta.persistence.EntityManager.class.getResource("EntityManager.class").toString().contains("jakarta.persistence-api-" + ApiValidation.environments().value(ApiValidation.environments().primary().input() + ".jpaVersion") + ".jar"));
    }

    @Test void missingAttributionNeverEditsAndReportsAgainOnNewExecution() {
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
        SourceFile stripped = (SourceFile) new JavaIsoVisitor<Integer>() {
            @Override public J.Annotation visitAnnotation(J.Annotation a, Integer p) { return super.visitAnnotation(a, p).withType(null); }
            @Override public J.MethodInvocation visitMethodInvocation(J.MethodInvocation m, Integer p) { return super.visitMethodInvocation(m, p).withMethodType(null).withName(m.getName().withType(null)); }
        }.visit(parsed, 0);
        var recipe = ApiValidation.composite("org.hibernate.migration.recipes.jpa4");
        for (int attempt = 0; attempt < 2; attempt++) {
            var run = recipe.run(new InMemoryLargeSourceSet(List.of(stripped)), new InMemoryExecutionContext(t -> fail(t)));
            assertEquals(0, run.getChangeset().size());
            var rows = run.getDataTableRows(SkippedMigrations.class);
            assertEquals(3, rows.size());
            assertTrue(rows.stream().allMatch(r -> r.getReasonCode().equals("MISSING_TYPE_ATTRIBUTION")));
        }
    }

    @Test void invalidAnnotationStructuresAreReportedWithoutExceptions() {
        for (var entry : Map.of(
                "@jakarta.persistence.NamedQueries(value=\"bad\") class Example {}", "UNSUPPORTED_ANNOTATION_SHAPE",
                "@jakarta.persistence.NamedQuery(name=\"a\", name=\"b\", query=\"delete from t\") class Example {}", "UNSUPPORTED_ANNOTATION_SHAPE",
                "class Example { @jakarta.persistence.NamedQuery(name=\"a\", query=\"delete from t\") Object field; }", "UNSUPPORTED_ANNOTATION_LOCATION"
        ).entrySet()) {
            var ctx = new InMemoryExecutionContext(t -> fail(t));
            SourceFile source = ApiValidation.parser("jpa32").build().parse(ctx, entry.getKey()).findFirst().orElseThrow();
            assertInstanceOf(J.CompilationUnit.class, source);
            var run = new MigrateNamedQueryToStatement().run(new InMemoryLargeSourceSet(List.of(source)), ctx);
            assertEquals(0, run.getChangeset().size());
            assertEquals(1, run.getDataTableRows(SkippedMigrations.class).size());
            assertEquals(entry.getValue(), run.getDataTableRows(SkippedMigrations.class).getFirst().getReasonCode());
        }
    }

    @Test void identicalCandidatesAtDifferentLocationsAndCrLf() {
        String source = "import jakarta.persistence.*;\r\n@NamedQuery(name=\"same\", query=\"with unsupported\") class A {}\r\n@NamedQuery(name=\"same\", query=\"with unsupported\") class B {}\r\n";
        var result = ApiValidation.run(new MigrateNamedQueryToStatement(), source, "jpa32");
        assertEquals(source, result.text());
        assertEquals(List.of(2, 3), result.skipped().stream().map(r -> r.getLine()).sorted().toList());
        assertTrue(result.skipped().stream().allMatch(r -> r.getColumn() == 1));
    }
}
