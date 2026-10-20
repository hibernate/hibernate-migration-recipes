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
            assertFalse(new String(jar.getInputStream(jar.getEntry("META-INF/rewrite/orm80.yml")).readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8).contains("@orm80TargetVersion@"));
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
            for (String simple : List.of("MigrateLoadAccess", "MigrateById", "MigrateByMultipleIds", "MigrateByNaturalId", "MigrateBySimpleNaturalId", "MigrateByMultipleNaturalId", "MigrateMappingXml", "MigrateOptimisticLockExclusion", "MigrateSqlResultVerification", "MigrateQueryFlushMode", "MigrateQueryHints", "MigrateFindMultipleOptions", "MigrateClientEnhancementOption", "MigrateGradleClientEnhancementOption", "MigrateMavenClientEnhancementOption", "MigrateAntClientEnhancementOption", "MigrateOrmCoordinates", "MigrateGradleOrmCoordinates", "MigrateMavenOrmCoordinates", "MigrateAntOrmCoordinates")) {
                String name = "org.hibernate.migration.recipes.orm80." + simple;
                assertTrue(names.contains(name), names.toString());
                assertEquals(name, environment.activateRecipes(name).getName());
                assertEquals(path.toUri().toURL(), loader.loadClass(name).getProtectionDomain().getCodeSource().getLocation());
            }
            for (String simple : List.of("AdoptHibernatePlatform")) {
                String name = "org.hibernate.migration.recipes.platform." + simple;
                assertTrue(names.contains(name), names.toString());
                assertEquals(name, environment.activateRecipes(name).getName());
                assertEquals(path.toUri().toURL(), loader.loadClass(name).getProtectionDomain().getCodeSource().getLocation());
            }
            assertFalse(names.contains("org.hibernate.migration.recipes.jpa4.MigratePersistencePackageDescriptors"));
            assertFalse(names.contains("org.hibernate.migration.recipes.orm80.MigrateMappingXmlComments"));
            assertFalse(names.contains("org.hibernate.migration.recipes.jpa4"));
            List<String> required = List.of(
                    "org.hibernate.migration.recipes.jpa4.MigrateNamedQueryToStatement",
                    "org.hibernate.migration.recipes.jpa4.MigratePersistenceXml",
                    "org.hibernate.migration.recipes.orm80.MigrateMappingXml",
                    "org.hibernate.migration.recipes.orm80.MigrateQueryFlushMode",
                    "org.hibernate.migration.recipes.orm80.MigrateQueryHints",
                    "org.hibernate.migration.recipes.orm80.MigrateFindMultipleOptions",
                    "org.hibernate.migration.recipes.orm80.MigrateSqlResultVerification",
                    "org.hibernate.migration.recipes.orm80.MigrateOrmCoordinates"
            );
            List<String> comprehensive = List.of(
                    "org.hibernate.migration.recipes.orm80-required",
                    "org.hibernate.migration.recipes.orm80.MigrateLoadAccess",
                    "org.hibernate.migration.recipes.jpa4.MigrateOrmXml",
                    "org.hibernate.migration.recipes.jpa4.MigrateEntityManagerGetDelegate",
                    "org.hibernate.migration.recipes.jpa4.MigrateMapKeyNameToValue",
                    "org.hibernate.migration.recipes.orm80.MigrateOptimisticLockExclusion",
                    "org.hibernate.migration.recipes.orm80.MigrateClientEnhancementOption"
            );
            for (String name : List.of("org.hibernate.migration.recipes.orm80-required", "org.hibernate.migration.recipes.orm80")) {
                assertTrue(names.contains(name));
                Recipe composite = environment.activateRecipes(name);
                assertEquals(name, composite.getName());
                boolean requiredOnly = name.endsWith("-required");
                assertEquals(requiredOnly ? required : comprehensive,
                        composite.getRecipeList().stream().map(Recipe::getName).toList());
                List<Recipe> tree = descendants(composite);
                for (Recipe recipe : tree) assertTrue(recipe.validate().isValid(), recipe.getName());
                List<String> selected = tree.stream().map(Recipe::getName).toList();
                List<String> expected = new ArrayList<>(required);
                if (!requiredOnly) {
                    expected.addAll(comprehensive.subList(1, comprehensive.size()));
                    for (String leaf : List.of("ById", "ByMultipleIds", "ByNaturalId", "BySimpleNaturalId", "ByMultipleNaturalId")) {
                        expected.add("org.hibernate.migration.recipes.orm80.Migrate" + leaf);
                    }
                }
                for (String selectedName : expected) {
                    assertEquals(1, Collections.frequency(selected, selectedName), selectedName);
                }
                assertFalse(selected.contains("org.hibernate.migration.recipes.temporal.MigrateTemporalAnnotation"));
                assertFalse(selected.contains("org.hibernate.migration.recipes.platform.AdoptHibernatePlatform"));
                assertFalse(names.contains(name + "-minimal"));
                List<Recipe> coordinates = tree.stream().filter(recipe -> recipe.getName().equals(
                        "org.hibernate.migration.recipes.orm80.MigrateOrmCoordinates")).toList();
                assertEquals(1, coordinates.size());
                var validation = ValidationEnvironment.configured();
                assertEquals(validation.value(validation.target("orm80") + ".ormVersion"),
                        coordinates.getFirst().getClass().getMethod("getTargetVersion").invoke(coordinates.getFirst()));
                // Every migration selected by the comprehensive composite is accounted for.
                var migrations = selected.stream().filter(candidate -> candidate.startsWith("org.hibernate.migration.recipes."))
                        .filter(candidate -> !candidate.equals("org.hibernate.migration.recipes.orm80-required"))
                        .filter(candidate -> !candidate.matches(".*\\.Migrate(Gradle|Maven|Ant)(OrmCoordinates|ClientEnhancementOption)"))
                        .toList();
                assertEquals(new HashSet<>(expected), new HashSet<>(migrations));
            }
            assertFalse(names.contains("org.hibernate.migration.recipes.jpa4.MigrateTemporalAnnotation"));
            assertFalse(names.contains("org.hibernate.migration.recipes.MigrateJpa3To4"));
            assertFalse(names.contains("org.hibernate.migration.recipes.MigrateHibernateOrm7To8"));
        }
    }

    private static List<Recipe> descendants(Recipe recipe) {
        List<Recipe> result = new ArrayList<>();
        for (Recipe child : recipe.getRecipeList()) {
            result.add(child);
            result.addAll(descendants(child));
        }
        return result;
    }
}
