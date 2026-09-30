package org.hibernate.migration.recipes.jpa4;

import org.hibernate.migration.testing.ApiValidation;
import org.hibernate.migration.testing.MigrationSources;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.xml.Assertions.xml;

/// Regression coverage for the migration recipe.
/// @author Steve Ebersole
class MigrateJpa3To4Test implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        ApiValidation.validateChangedJavaSourcesAfterRecipe(spec);
        spec
                .recipeFromResources("org.hibernate.migration.recipes.orm80")
                .parser(JavaParser.fromJavaVersion()
                        .logCompilationWarningsAndErrors(true)
                        .classpath("jakarta.persistence-api"));
    }

    @Test
    void allThreeMigrationsAppliedTogether() {
        rewriteRun(
                //language=java
                java(
                MigrationSources.configured().read("fixture/jpa4/jpa3to4/allthreemigrationsappliedtogether/Book.java"),
                """
                package fixture.jpa4.jpa3to4.allthreemigrationsappliedtogether;

                import jakarta.persistence.Entity;
                import jakarta.persistence.EntityManager;
                import jakarta.persistence.Id;
                import jakarta.persistence.NamedStatement;

                @NamedStatement(name = "Book.deleteOld", statement = "delete from Book where year < 2000")
                @Entity
                class Book {
                \t@Id
                \tLong id;

                \tvoid clean(EntityManager em) {
                \t\tObject delegate = em.unwrap(java.lang.Object.class);
                \t}
                }
                """
          ),
                //language=xml
                xml(
                        """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <persistence version="3.2"
                                     xmlns="https://jakarta.ee/xml/ns/persistence"
                                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                     xsi:schemaLocation="https://jakarta.ee/xml/ns/persistence https://jakarta.ee/xml/ns/persistence/persistence_3_2.xsd">
                            <persistence-unit name="my-unit">
                            </persistence-unit>
                        </persistence>
                        """,
                        """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <persistence version="4.0"
                                     xmlns="https://jakarta.ee/xml/ns/persistence"
                                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                     xsi:schemaLocation="https://jakarta.ee/xml/ns/persistence https://jakarta.ee/xml/ns/persistence/persistence_4_0.xsd">
                            <persistence-unit name="my-unit">
                            </persistence-unit>
                        </persistence>
                        """
                )
        );
    }
}
