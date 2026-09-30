package org.hibernate.migration.recipes.jpa4;

import org.hibernate.migration.testing.ApiValidation;
import org.hibernate.migration.testing.MigrationSources;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

/// Regression coverage for the migration recipe.
/// @author Steve Ebersole
class MigrateEntityManagerGetDelegateTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        ApiValidation.validateChangedJavaSourcesAfterRecipe(spec);
        spec
                .recipe(new MigrateEntityManagerGetDelegate())
                .parser(org.openrewrite.java.JavaParser.fromJavaVersion()
                        .logCompilationWarningsAndErrors(true)
                        .classpath("jakarta.persistence-api"));
    }

    @DocumentExample
    @Test
    void getdelegateInAssignmentReplacedWithUnwrap() {
        rewriteRun(
                //language=java
                java(
                MigrationSources.configured().read("fixture/jpa4/entitymanagergetdelegate/getdelegateinassignmentreplacedwithunwrap/MyService.java"),
                """
                package fixture.jpa4.entitymanagergetdelegate.getdelegateinassignmentreplacedwithunwrap;

                import jakarta.persistence.EntityManager;

                class MyService {
                \tvoid method(EntityManager em) {
                \t\tObject delegate = em.unwrap(java.lang.Object.class);
                \t}
                }
                """
          )
        );
    }

    @Test
    void getdelegateWithCastReplacedWithUnwrap() {
        rewriteRun(
                //language=java
                java(
                MigrationSources.configured().read("fixture/jpa4/entitymanagergetdelegate/getdelegatewithcastreplacedwithunwrap/MyService.java"),
                """
                package fixture.jpa4.entitymanagergetdelegate.getdelegatewithcastreplacedwithunwrap;

                import jakarta.persistence.EntityManager;

                class MyService {
                \tvoid method(EntityManager em) {
                \t\tObject delegate = (Object) em.unwrap(java.lang.Object.class);
                \t}
                }
                """
          )
        );
    }

    @Test
    void getdelegateInReturnStatementReplacedWithUnwrap() {
        rewriteRun(
                //language=java
                java(
                MigrationSources.configured().read("fixture/jpa4/entitymanagergetdelegate/getdelegateinreturnstatementreplacedwithunwrap/MyService.java"),
                """
                package fixture.jpa4.entitymanagergetdelegate.getdelegateinreturnstatementreplacedwithunwrap;

                import jakarta.persistence.EntityManager;

                class MyService {
                \tObject getUnderlying(EntityManager em) {
                \t\treturn em.unwrap(java.lang.Object.class);
                \t}
                }
                """
          )
        );
    }

    @Test
    void getDelegateOnNonEntityManagerIsNotChanged() {
        rewriteRun(
                //language=java
                java(
                MigrationSources.configured().read("fixture/jpa4/entitymanagergetdelegate/getdelegateonnonentitymanagerisnotchanged/OtherManager.java")
          )
        );
    }
}
