/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.migration.recipes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class MigrateTemporalAnnotationTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec
                .recipe(new MigrateTemporalAnnotation())
                .parser(JavaParser.fromJavaVersion()
                        .logCompilationWarningsAndErrors(true)
                        .classpath("jakarta.persistence-api"));
    }

    @DocumentExample
    @Test
    void temporalDateOnDateFieldBecomesLocalDate() {
        rewriteRun(
          //language=java
          java(
            """
            import jakarta.persistence.Temporal;
            import jakarta.persistence.TemporalType;
            import java.util.Date;

            class MyEntity {
                @Temporal(TemporalType.DATE)
                private Date birthday;
            }
            """,
            """
            import java.time.LocalDate;

            class MyEntity {
                private LocalDate birthday;
            }
            """
          )
        );
    }

    @Test
    void temporalTimeOnDateFieldBecomesLocalTime() {
        rewriteRun(
          //language=java
          java(
            """
            import jakarta.persistence.Temporal;
            import jakarta.persistence.TemporalType;
            import java.util.Date;

            class MyEntity {
                @Temporal(TemporalType.TIME)
                private Date startTime;
            }
            """,
            """
            import java.time.LocalTime;

            class MyEntity {
                private LocalTime startTime;
            }
            """
          )
        );
    }

    @Test
    void temporalTimestampOnDateFieldBecomesLocalDateTime() {
        rewriteRun(
          //language=java
          java(
            """
            import jakarta.persistence.Temporal;
            import jakarta.persistence.TemporalType;
            import java.util.Date;

            class MyEntity {
                @Temporal(TemporalType.TIMESTAMP)
                private Date createdAt;
            }
            """,
            """
            import java.time.LocalDateTime;

            class MyEntity {
                private LocalDateTime createdAt;
            }
            """
          )
        );
    }

    @ParameterizedTest
    @CsvSource({
      "DATE,      java.util.Calendar, LocalDate,     java.time.LocalDate",
      "TIME,      java.util.Calendar, LocalTime,     java.time.LocalTime",
      "TIMESTAMP, java.util.Calendar, LocalDateTime, java.time.LocalDateTime"
    })
    void temporalOnCalendarField(String temporalType, String oldType,
                                 String newSimple, String newFqn) {
        rewriteRun(
          //language=java
          java(
            """
            import jakarta.persistence.Temporal;
            import jakarta.persistence.TemporalType;
            import %s;

            class MyEntity {
                @Temporal(TemporalType.%s)
                private %s field;
            }
            """.formatted(oldType, temporalType, "Calendar"),
            """
            import %s;

            class MyEntity {
                private %s field;
            }
            """.formatted(newFqn, newSimple)
          )
        );
    }

    @Test
    void otherAnnotationsArePreserved() {
        rewriteRun(
          //language=java
          java(
            """
            import jakarta.persistence.Basic;
            import jakarta.persistence.Column;
            import jakarta.persistence.Temporal;
            import jakarta.persistence.TemporalType;
            import java.util.Date;

            class MyEntity {
                @Basic
                @Column(name = "BIRTHDAY")
                @Temporal(TemporalType.DATE)
                private Date birthday;
            }
            """,
            """
            import jakarta.persistence.Basic;
            import jakarta.persistence.Column;

            import java.time.LocalDate;

            class MyEntity {
                @Basic
                @Column(name = "BIRTHDAY")
                private LocalDate birthday;
            }
            """
          )
        );
    }

    @Test
    void temporalOnNonDateTypeIsNotChanged() {
        rewriteRun(
          //language=java
          java(
            """
            import jakarta.persistence.Temporal;
            import jakarta.persistence.TemporalType;

            class MyEntity {
                @Temporal(TemporalType.DATE)
                private String notADate;
            }
            """
          )
        );
    }

    @Test
    void fieldAlreadyUsingLocalDateIsNotChanged() {
        rewriteRun(
          //language=java
          java(
            """
            import java.time.LocalDate;

            class MyEntity {
                private LocalDate birthday;
            }
            """
          )
        );
    }

    @Test
    void temporalOnMethodIsNotChanged() {
        rewriteRun(
          //language=java
          java(
            """
            import jakarta.persistence.Temporal;
            import jakarta.persistence.TemporalType;
            import java.util.Date;

            class MyEntity {
                @Temporal(TemporalType.DATE)
                public Date getBirthday() { return null; }
            }
            """
          )
        );
    }
}
