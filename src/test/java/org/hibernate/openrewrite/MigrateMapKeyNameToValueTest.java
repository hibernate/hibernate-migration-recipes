/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.openrewrite;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class MigrateMapKeyNameToValueTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
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
        rewriteRun(
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
        rewriteRun(
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
