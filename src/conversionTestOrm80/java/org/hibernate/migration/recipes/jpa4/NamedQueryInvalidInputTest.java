package org.hibernate.migration.recipes.jpa4;

import org.hibernate.migration.testing.ApiValidation;
import org.hibernate.migration.recipes.table.SkippedMigrations;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.SourceFile;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.java.tree.J;
import java.util.List;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/// Checks deliberately malformed annotation inputs through parsing; these fixtures cannot be compiler-validated.
/// @author Steve Ebersole
class NamedQueryInvalidInputTest {
    static Stream<Arguments> invalidAnnotations() {
        return Stream.of(
                Arguments.of("non-annotation container value", "@jakarta.persistence.NamedQueries(value=\"bad\") class Example {}", "UNSUPPORTED_ANNOTATION_SHAPE"),
                Arguments.of("duplicate attribute", "@jakarta.persistence.NamedQuery(name=\"a\", name=\"b\", query=\"delete from t\") class Example {}", "UNSUPPORTED_ANNOTATION_SHAPE"),
                Arguments.of("annotation on field", "class Example { @jakarta.persistence.NamedQuery(name=\"a\", query=\"delete from t\") Object field; }", "UNSUPPORTED_ANNOTATION_LOCATION")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidAnnotations")
    void invalidAnnotationIsReportedWithoutEditing(String name, String input, String reason) {
        // These shapes are intentionally invalid Java/JPA usage: exercise the parser directly.
        var ctx = new InMemoryExecutionContext(t -> fail(t));
        SourceFile source = ApiValidation.parser("orm74").build().parse(ctx, input).findFirst().orElseThrow();
        assertInstanceOf(J.CompilationUnit.class, source, name);
        var run = new MigrateNamedQueryToStatement().run(new InMemoryLargeSourceSet(List.of(source)), ctx);
        assertEquals(0, run.getChangeset().size(), name);
        var rows = run.getDataTableRows(SkippedMigrations.class);
        assertEquals(1, rows.size(), name);
        assertEquals(reason, rows.getFirst().getReasonCode(), name);
    }
}
