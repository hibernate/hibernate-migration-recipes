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
class MigrateNamedQueryToStatementTest implements RewriteTest {

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
	void dmlNamedQueryBecomesNamedStatement() {
		rewriteRun(
		  //language=java
		  java(
                MigrationSources.configured().read("fixture/jpa4/namedquerytostatement/dmlnamedquerybecomesnamedstatement/Book.java"),
                """
                package fixture.jpa4.namedquerytostatement.dmlnamedquerybecomesnamedstatement;

                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;
                import jakarta.persistence.NamedStatement;

                @NamedStatement(name = "Book.deleteOld", statement = "delete from Book where year < 2000")
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
	void selectNamedQueryIsNotChanged() {
		rewriteRun(
		  //language=java
		  java(
                MigrationSources.configured().read("fixture/jpa4/namedquerytostatement/selectnamedqueryisnotchanged/Book.java")
          )
		);
	}

	@Test
	void mixedNamedQueriesContainerSplitsDmlOut() {
		rewriteRun(
		  //language=java
		  java(
                MigrationSources.configured().read("fixture/jpa4/namedquerytostatement/mixednamedqueriescontainersplitsdmlout/Book.java"),
                """
                package fixture.jpa4.namedquerytostatement.mixednamedqueriescontainersplitsdmlout;

                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;
                import jakarta.persistence.NamedQueries;
                import jakarta.persistence.NamedQuery;
                import jakarta.persistence.NamedStatement;

                @NamedQueries({
                \t\t@NamedQuery(name = "Book.findAll", query = "select b from Book b")
                })
                @NamedStatement(name = "Book.deleteOld", statement = "delete from Book where year < 2000")
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
	void allDmlNamedQueriesContainerFullyReplaced() {
		rewriteRun(
		  //language=java
		  java(
                MigrationSources.configured().read("fixture/jpa4/namedquerytostatement/alldmlnamedqueriescontainerfullyreplaced/Book.java"),
                """
                package fixture.jpa4.namedquerytostatement.alldmlnamedqueriescontainerfullyreplaced;

                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;
                import jakarta.persistence.NamedStatement;

                @NamedStatement(name = "Book.deleteOld", statement = "delete from Book where year < 2000")
                @NamedStatement(name = "Book.updateTitle", statement = "update Book set title = :t where id = :id")
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
	void containerAndStandaloneOnSameClass() {
		rewriteRun(
		  //language=java
		  java(
                MigrationSources.configured().read("fixture/jpa4/namedquerytostatement/containerandstandaloneonsameclass/Book.java"),
                """
                package fixture.jpa4.namedquerytostatement.containerandstandaloneonsameclass;

                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;
                import jakarta.persistence.NamedQueries;
                import jakarta.persistence.NamedQuery;
                import jakarta.persistence.NamedStatement;

                @NamedQueries({
                \t\t@NamedQuery(name = "Book.findAll", query = "select b from Book b")
                })
                @NamedStatement(name = "Book.deleteOld", statement = "delete from Book where year < 2000")
                @NamedStatement(name = "Book.updateTitle", statement = "update Book set title = :t")
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
	void allSelectNamedQueriesContainerUnchanged() {
		rewriteRun(
		  //language=java
		  java(
                MigrationSources.configured().read("fixture/jpa4/namedquerytostatement/allselectnamedqueriescontainerunchanged/Book.java")
          )
		);
	}

	@Test
	void dmlNamedNativeQueryBecomesNamedNativeStatement() {
		rewriteRun(
		  //language=java
		  java(
                MigrationSources.configured().read("fixture/jpa4/namedquerytostatement/dmlnamednativequerybecomesnamednativestatement/Book.java"),
                """
                package fixture.jpa4.namedquerytostatement.dmlnamednativequerybecomesnamednativestatement;

                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;
                import jakarta.persistence.NamedNativeStatement;

                @NamedNativeStatement(name = "Book.nativeDelete", statement = "delete from BOOK where year < 2000")
                @Entity
                class Book {
                \t@Id
                \tLong id;
                }
                """
          )
		);
	}
}
