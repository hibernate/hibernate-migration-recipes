package org.hibernate.migration.testing;

import org.junit.jupiter.api.Test;
import org.openrewrite.Recipe;
import org.openrewrite.config.Environment;
import java.nio.file.*;
import java.net.URLClassLoader;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;

/// Checks packaging and discovery of the recipes for the ORM 7.4 to 8.0 migration.
/// @author Steve Ebersole
class RecipeArtifactTest {
    @Test void jarContainsOnlyProductionClassesAndDiscoverableRecipes() throws Exception {
        Path path = Path.of(System.getProperty("recipeJar"));
        try (ZipFile jar = new ZipFile(path.toFile())) {
            var names = jar.stream().map( ZipEntry::getName ).toList();
            assertFalse(names.contains("META-INF/rewrite/jpa4.yml"));
            assertTrue(names.contains("META-INF/rewrite/orm80.yml"));
            for (String name : names) {
                assertFalse(name.contains("/testing/"), name);
                assertFalse(name.startsWith("fixture/"), name);
                assertFalse(name.contains("Test.class") || name.contains("Fixture") || name.contains("ApiValidation"), name);
            }
        }
        // Child-first only for recipe classes ensures discovery loads them from the JAR, not main's classes directory.
        try (var loader = new URLClassLoader(new java.net.URL[]{path.toUri().toURL()}, getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("org.hibernate.migration.recipes.")) {
                    Class<?> c = findLoadedClass(name);
                    if (c == null) c = findClass(name);
                    if (resolve) resolveClass(c);
                    return c;
                }
                return super.loadClass(name, resolve);
            }
        }) {
            var environment = Environment.builder().scanJar(path, List.of(), loader).build();
            var names = environment.listRecipes().stream().map( Recipe::getName ).toList();
            for (String simple : List.of("MigrateNamedQueryToStatement", "MigrateMapKeyNameToValue", "MigrateEntityManagerGetDelegate", "MigrateTemporalAnnotation", "MigratePersistenceXml", "MigrateOrmXml")) {
                String recipePackage = simple.equals("MigrateTemporalAnnotation") ? "temporal" : "jpa4";
                String name = "org.hibernate.migration.recipes." + recipePackage + "." + simple;
                assertTrue(names.contains(name), names.toString());
                assertEquals(name, environment.activateRecipes(name).getName());
                assertEquals(path.toUri().toURL(), loader.loadClass(name).getProtectionDomain().getCodeSource().getLocation());
                assertFalse(names.contains("org.hibernate.migration.recipes." + simple));
            }
            for (String simple : List.of("MigrateMappingXml", "MigrateQueryFlushMode", "MigrateFindMultipleOptions", "MigrateClientEnhancementOption", "MigrateGradleClientEnhancementOption", "MigrateMavenClientEnhancementOption", "MigrateAntClientEnhancementOption", "MigrateOrmCoordinates", "MigrateGradleOrmCoordinates", "MigrateMavenOrmCoordinates", "MigrateAntOrmCoordinates")) {
                String name = "org.hibernate.migration.recipes.orm80." + simple;
                assertTrue(names.contains(name), names.toString());
                assertEquals(name, environment.activateRecipes(name).getName());
                assertEquals(path.toUri().toURL(), loader.loadClass(name).getProtectionDomain().getCodeSource().getLocation());
            }
            assertFalse(names.contains("org.hibernate.migration.recipes.jpa4.MigratePersistencePackageDescriptors"));
            String flushRecipe = "org.hibernate.migration.recipes.orm80.MigrateQueryFlushMode";
            assertTrue(environment.activateRecipes("org.hibernate.migration.recipes.orm80")
                    .getRecipeList().stream().anyMatch(recipe -> recipe.getName().equals(flushRecipe)));
            assertFalse(names.contains("org.hibernate.migration.recipes.orm80.MigrateMappingXmlComments"));
            assertFalse(names.contains("org.hibernate.migration.recipes.jpa4"));
            assertEquals(List.of(
                    "org.hibernate.migration.recipes.jpa4.MigrateNamedQueryToStatement",
                    "org.hibernate.migration.recipes.jpa4.MigratePersistenceXml",
                    "org.hibernate.migration.recipes.jpa4.MigrateOrmXml",
                    "org.hibernate.migration.recipes.jpa4.MigrateEntityManagerGetDelegate",
                    "org.hibernate.migration.recipes.jpa4.MigrateMapKeyNameToValue",
                    "org.hibernate.migration.recipes.orm80.MigrateMappingXml",
                    "org.hibernate.migration.recipes.orm80.MigrateQueryFlushMode",
                    "org.hibernate.migration.recipes.orm80.MigrateFindMultipleOptions",
                    "org.hibernate.migration.recipes.orm80.MigrateClientEnhancementOption",
                    "org.hibernate.migration.recipes.orm80.MigrateOrmCoordinates"
            ), environment.activateRecipes("org.hibernate.migration.recipes.orm80").getRecipeList().stream().map(Recipe::getName).toList());
            Recipe coordinates = environment.activateRecipes("org.hibernate.migration.recipes.orm80").getRecipeList().getLast();
            var validation = ValidationEnvironment.configured();
            assertEquals(validation.value(validation.target("orm80") + ".ormVersion"),
                    coordinates.getClass().getMethod("getTargetVersion").invoke(coordinates));
            assertTrue(coordinates.validate().isValid());
            assertFalse(names.contains("org.hibernate.migration.recipes.jpa4.MigrateTemporalAnnotation"));
            assertFalse(names.contains("org.hibernate.migration.recipes.MigrateJpa3To4"));
            assertFalse(names.contains("org.hibernate.migration.recipes.MigrateHibernateOrm7To8"));
        }
    }
}
