package org.hibernate.migration.testing;

import org.hibernate.migration.recipes.jpa4.MigrateMapKeyNameToValue;
import org.junit.jupiter.api.Test;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies that compilation failures identify the validation phase and API environment.
/// @author Steve Ebersole
class ApiValidationTest {
    @Test void invalidSourceIdentifiesSourceCompilation() {
        var failure = assertThrows(AssertionError.class, () -> ApiValidation.run(
                new MigrateMapKeyNameToValue(), "class Example { MissingType value; }", "orm74"));
        assertTrue(failure.getMessage().contains("source compilation [orm74]"), failure.getMessage());
        assertTrue(failure.getMessage().contains("Example.java"), failure.getMessage());
        assertNotNull(failure.getCause());
    }

    @Test void invalidOutputIdentifiesTargetCompilation() {
        // Renaming the class leaves its constructor with the old name. Types remain attributed,
        // so target compilation, rather than source compilation or attribution, rejects it.
        Recipe invalidOutput = new Recipe() {
            @Override public String getDisplayName() { return "Produce invalid test output"; }
            @Override public String getDescription() { return "Exercises target compilation diagnostics."; }
            @Override public TreeVisitor<?, ExecutionContext> getVisitor() {
                return new JavaIsoVisitor<ExecutionContext>() {
                    @Override public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration c, ExecutionContext ctx) {
                        return c.withName(c.getName().withSimpleName("Renamed"));
                    }
                };
            }
        };
        var failure = assertThrows(AssertionError.class, () -> ApiValidation.run(
                invalidOutput, "class Example { Example() {} }", "orm74"));
        String target = ApiValidation.environments().context("orm74").target();
        assertTrue(failure.getMessage().contains("target compilation [" + target + "]"), failure.getMessage());
        assertTrue(failure.getMessage().contains("Example.java"), failure.getMessage());
        assertNotNull(failure.getCause());
    }
}
