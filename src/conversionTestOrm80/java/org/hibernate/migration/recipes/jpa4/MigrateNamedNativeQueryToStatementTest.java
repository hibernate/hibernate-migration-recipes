package org.hibernate.migration.recipes.jpa4;

import org.hibernate.migration.testing.ApiValidation;
import org.hibernate.migration.testing.MigrationSources;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.Tree;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.style.IntelliJ;
import org.openrewrite.style.NamedStyles;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import java.util.Collections;
import java.util.List;

import static org.openrewrite.java.Assertions.java;

/// Regression coverage for the migration recipe.
/// @author Steve Ebersole
class MigrateNamedNativeQueryToStatementTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        ApiValidation.validateChangedJavaSourcesAfterRecipe(spec);
        spec
                .recipe(new MigrateNamedQueryToStatement())
                .parser(JavaParser.fromJavaVersion()
                        .logCompilationWarningsAndErrors(true)
                        .styles(List.of(new NamedStyles(
                                Tree.randomId(), "custom", "", "", Collections.emptySet(),
                                List.of(IntelliJ.importLayout()
                                        .withClassCountToUseStarImport(999)
                                        .withNameCountToUseStarImport(999))
                        )))
                        .classpath("jakarta.persistence-api"));
    }

    @DocumentExample
    @Test
    void dmlNamedNativeQueryBecomesNamedNativeStatement() {
        rewriteRun(
                //language=java
                java(
                MigrationSources.configured().read("fixture/jpa4/namednativequerytostatement/dmlnamednativequerybecomesnamednativestatement/Book.java"),
                """
                package fixture.jpa4.namednativequerytostatement.dmlnamednativequerybecomesnamednativestatement;

                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;
                import jakarta.persistence.NamedNativeStatement;

                @NamedNativeStatement(name = "Book.nativeDelete", statement = "DELETE FROM BOOK WHERE year < 2000")
                @Entity
                class Book {
                \t@Id
                \tLong id;
                }
                """
          )
        );
    }

    @Test
    void selectNamedNativeQueryIsNotChanged() {
        rewriteRun(
                //language=java
                java(
                MigrationSources.configured().read("fixture/jpa4/namednativequerytostatement/selectnamednativequeryisnotchanged/Book.java")
          )
        );
    }

    @Test
    void updateNamedNativeQueryBecomesNamedNativeStatement() {
        rewriteRun(
                //language=java
                java(
                MigrationSources.configured().read("fixture/jpa4/namednativequerytostatement/updatenamednativequerybecomesnamednativestatement/Book.java"),
                """
                package fixture.jpa4.namednativequerytostatement.updatenamednativequerybecomesnamednativestatement;

                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;
                import jakarta.persistence.NamedNativeStatement;

                @NamedNativeStatement(name = "Book.nativeUpdate", statement = "UPDATE BOOK SET title = :t WHERE id = :id")
                @Entity
                class Book {
                \t@Id
                \tLong id;
                }
                """
          )
        );
    }

    @Test
    void mixedNamedNativeQueriesContainerSplitsDmlOut() {
        rewriteRun(
                //language=java
                java(
                MigrationSources.configured().read("fixture/jpa4/namednativequerytostatement/mixednamednativequeriescontainersplitsdmlout/Book.java"),
                """
                package fixture.jpa4.namednativequerytostatement.mixednamednativequeriescontainersplitsdmlout;

                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;
                import jakarta.persistence.NamedNativeQueries;
                import jakarta.persistence.NamedNativeQuery;
                import jakarta.persistence.NamedNativeStatement;

                @NamedNativeQueries({
                \t\t@NamedNativeQuery(name = "Book.findAll", query = "SELECT * FROM BOOK")
                })
                @NamedNativeStatement(name = "Book.deleteOld", statement = "DELETE FROM BOOK WHERE year < 2000")
                @Entity
                class Book {
                \t@Id
                \tLong id;
                }
                """
          )
        );
    }

    @Test
    void allDmlNamedNativeQueriesContainerFullyReplaced() {
        rewriteRun(
                //language=java
                java(
                MigrationSources.configured().read("fixture/jpa4/namednativequerytostatement/alldmlnamednativequeriescontainerfullyreplaced/Book.java"),
                """
                package fixture.jpa4.namednativequerytostatement.alldmlnamednativequeriescontainerfullyreplaced;

                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;
                import jakarta.persistence.NamedNativeStatement;

                @NamedNativeStatement(name = "Book.nativeDelete", statement = "DELETE FROM BOOK WHERE year < 2000")
                @NamedNativeStatement(name = "Book.nativeUpdate", statement = "UPDATE BOOK SET title = :t WHERE id = :id")
                @Entity
                class Book {
                \t@Id
                \tLong id;
                }
                """
          )
        );
    }

    @Test
    void allSelectNamedNativeQueriesContainerUnchanged() {
        rewriteRun(
                //language=java
                java(
                MigrationSources.configured().read("fixture/jpa4/namednativequerytostatement/allselectnamednativequeriescontainerunchanged/Book.java")
          )
        );
    }
}
