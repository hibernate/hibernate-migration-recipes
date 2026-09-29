/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.migration.recipes.jpa4;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

/// Regression coverage for the migration recipe.
/// @author Steve Ebersole
class MigrateMapKeyNameToValueTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        ApiValidation.verifyBaseline(spec);
        spec
                .recipe(new MigrateMapKeyNameToValue())
                .parser(JavaParser.fromJavaVersion()
                        .logCompilationWarningsAndErrors(true)
                        .classpath("jakarta.persistence-api"));
    }

    @DocumentExample
    @Test
    void mapKeyNameBecomesShorthandValue() {
        rewriteRun(
          //language=java
          java(
            """
            import jakarta.persistence.MapKey;
            import java.util.Map;

            class Department {
                @MapKey(name = "empId")
                Map<Integer, Object> employees;
            }
            """,
            """
            import jakarta.persistence.MapKey;
            import java.util.Map;

            class Department {
                @MapKey("empId")
                Map<Integer, Object> employees;
            }
            """
          )
        );
    }

    @Test
    void mapKeyWithoutAttributeIsNotChanged() {
        rewriteRun(
          //language=java
          java(
            """
            import jakarta.persistence.MapKey;
            import java.util.Map;

            class Department {
                @MapKey
                Map<Integer, Object> employees;
            }
            """
          )
        );
    }

    @Test
    void mapKeyValueAttributeAlreadyCorrectIsNotChanged() {
        rewriteRun(spec -> spec.parser(ApiValidation.parser(ApiValidation.environments().primary().target())),
          //language=java
          java(
            """
            import jakarta.persistence.MapKey;
            import java.util.Map;

            class Department {
                @MapKey(value = "empId")
                Map<Integer, Object> employees;
            }
            """
          )
        );
    }

    @Test
    void mapKeyShorthandAlreadyCorrectIsNotChanged() {
        rewriteRun(spec -> spec.parser(ApiValidation.parser(ApiValidation.environments().primary().target())),
          //language=java
          java(
            """
            import jakarta.persistence.MapKey;
            import java.util.Map;

            class Department {
                @MapKey("empId")
                Map<Integer, Object> employees;
            }
            """
          )
        );
    }
}
