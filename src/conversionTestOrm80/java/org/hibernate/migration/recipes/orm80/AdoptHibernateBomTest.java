package org.hibernate.migration.recipes.orm80;

import java.nio.file.Path;
import java.util.*;
import org.hibernate.migration.recipes.platform.AdoptHibernateBom;
import org.hibernate.migration.recipes.support.OrmCoordinateSupport;
import org.hibernate.migration.recipes.table.SkippedMigrations;
import org.hibernate.migration.testing.RecipeExecutionContexts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openrewrite.*;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.maven.MavenExecutionContextView;
import org.openrewrite.maven.tree.MavenRepositoryMirror;

import static org.hibernate.migration.recipes.orm80.OrmCoordinatesTest.*;
import static org.junit.jupiter.api.Assertions.*;

/// Verifies ORM-only BOM adoption, competing management, conservative coverage, and repeat runs.
///
/// @author Steve Ebersole
class AdoptHibernateBomTest {
    static Outcome adopt(Map<String, String> input, boolean migrate) {
        Recipe recipe = migrate ? new Recipe() {
            @Override public String getDisplayName() { return "Migration and BOM adoption"; }
            @Override public String getDescription() { return "Test composition."; }
            @Override public List<Recipe> getRecipeList() { return List.of(new MigrateOrmCoordinates(TARGET), new AdoptHibernateBom(TARGET)); }
        } : new AdoptHibernateBom(TARGET);
        var ctx = RecipeExecutionContexts.standard(e -> { throw new AssertionError(e); });
        var sources = input.entrySet().stream().map(e -> parse(e.getKey(), e.getValue(), ctx)).toList();
        var run = recipe.run(new InMemoryLargeSourceSet(sources), ctx);
        Map<String, String> output = new LinkedHashMap<>(input);
        for (Result result : run.getChangeset().getAllResults()) output.put(result.getAfter().getSourcePath().toString(), result.getAfter().printAll());
        var rows = run.getDataTableRows(SkippedMigrations.class);
        var secondContext = RecipeExecutionContexts.standard(e -> { throw new AssertionError(e); });
        var reparsed = output.entrySet().stream().map(e -> parse(e.getKey(), e.getValue(), secondContext)).toList();
        var second = recipe.run(new InMemoryLargeSourceSet(reparsed), secondContext);
        assertTrue(second.getChangeset().getAllResults().isEmpty(), "Second run changed output");
        if (rows.isEmpty()) assertTrue(second.getDataTableRows(SkippedMigrations.class).isEmpty(), "Second run introduced diagnostics");
        return new Outcome(output, rows);
    }
    private Outcome adopt(String path, String input) { return adopt(Map.of(path, input), false); }
    private static String dependency(String artifact, String version) {
        return "<dependency><groupId>org.hibernate.orm</groupId><artifactId>" + artifact + "</artifactId>"
                + (version.isEmpty() ? "" : "<version>" + version + "</version>") + "</dependency>";
    }
    private static String bom(String artifact, String version) {
        return dependency(artifact, version).replace("</dependency>", "<type>pom</type><scope>import</scope></dependency>");
    }
    private static String management(String dependencies) { return "<dependencyManagement><dependencies>" + dependencies + "</dependencies></dependencyManagement>"; }

    @Test void defaultsValidationAndPublishedMetadata() {
        assertEquals(TARGET, new AdoptHibernateBom().getTargetVersion());
        assertTrue(new AdoptHibernateBom().validate().isValid());
        assertFalse(new AdoptHibernateBom("8.0.+").validate().isValid());
        assertTrue(new AdoptHibernateBom().getDescriptor().getOptions().stream().anyMatch(o -> o.getName().equals("targetVersion")));
        var ctx = RecipeExecutionContexts.standard(e -> { throw new AssertionError(e); });
        assertTrue(OrmCoordinateSupport.managed(OrmCoordinateSupport.BOM, TARGET, List.of(), ctx).contains("hibernate-core"));
        assertTrue(OrmCoordinateSupport.managed(TARGET, List.of(), ctx).contains("hibernate-core"));
    }

    @ParameterizedTest @ValueSource(strings = {"build.gradle", "build.gradle.kts"})
    void gradleCoexistsWithApplicationPlatformAndPreservesOtherRoles(String path) {
        String input = "dependencies {\n"
                + "    implementation(platform(\"com.example:application-platform:1.0\"))\n"
                + "    implementation(\"org.hibernate.orm:hibernate-core:" + TARGET + "\")\n"
                + "    implementation(\"org.jboss.logging:jboss-logging:3.6.1.Final\")\n"
                + "    annotationProcessor(\"org.hibernate.orm:hibernate-processor:" + TARGET + "\")\n"
                + "    implementation(\"org.hibernate.orm:hibernate-core:" + TARGET + ":tests\")\n"
                + "    constraints { implementation(\"org.hibernate.orm:hibernate-envers:" + TARGET + "\") }\n}\n";
        var result = adopt(path, input);
        String output = result.files().get(path);
        assertTrue(result.rows().isEmpty(), result.rows().toString());
        assertTrue(output.contains("implementation(\"org.hibernate.orm:hibernate-core\")"), output);
        assertTrue(output.contains("implementation(platform(\"org.hibernate.orm:hibernate-bom:" + TARGET + "\"))"), output);
        for (String retained : List.of("com.example:application-platform:1.0", "org.jboss.logging:jboss-logging:3.6.1.Final",
                "hibernate-processor:" + TARGET, "hibernate-core:" + TARGET + ":tests", "hibernate-envers:" + TARGET)) assertTrue(output.contains(retained), output);
        assertFalse(output.contains("enforcedPlatform"));
    }

    @ParameterizedTest @ValueSource(strings = {"build.gradle", "build.gradle.kts"})
    void existingBomIsReusedIncludingEnforcedAndInheritedDeclarations(String path) {
        for (String wrapper : List.of("platform", "enforcedPlatform")) {
            String input = "dependencies { api(" + wrapper + "(\"org.hibernate.orm:hibernate-bom:" + TARGET
                    + "\")) }\ndependencies { implementation(\"org.hibernate.orm:hibernate-core:" + TARGET + "\") }\n";
            var result = adopt(path, input);
            assertEquals(input.replace("hibernate-core:" + TARGET, "hibernate-core"), result.files().get(path));
            assertTrue(result.rows().isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"build.gradle", "build.gradle.kts"})
    void newBomCoverageIsSharedAcrossInheritedConfigurations(String path) {
        String input = "dependencies {\n"
                + "    testImplementation(\"org.hibernate.orm:hibernate-envers:" + TARGET + "\")\n"
                + "    implementation(\"org.hibernate.orm:hibernate-core:" + TARGET + "\")\n"
                + "    api(\"org.hibernate.orm:hibernate-spatial:" + TARGET + "\")\n}\n";
        var result = adopt(path, input);
        assertTrue(result.rows().isEmpty(), result.rows().toString());
        assertEquals(1, result.files().get(path).split("hibernate-bom", -1).length - 1);
    }

    @ParameterizedTest @ValueSource(strings = {"build.gradle", "build.gradle.kts"})
    void platformScopesAreUntouchedAndIndependentScopesMigrate(String path) {
        String input = "dependencies {\n    compileOnly(platform(\"org.hibernate.orm:hibernate-platform:" + TARGET + "\"))\n"
                + "    implementation(\"org.hibernate.orm:hibernate-core:" + TARGET + "\")\n"
                + "    compileOnly(\"org.hibernate.orm:hibernate-envers:" + TARGET + "\")\n"
                + "    runtimeOnly(\"org.hibernate.orm:hibernate-spatial:" + TARGET + "\")\n}\n";
        var result = adopt(path, input);
        assertTrue(result.rows().isEmpty());
        String output = result.files().get(path);
        assertTrue(output.contains("hibernate-core:" + TARGET));
        assertTrue(output.contains("hibernate-envers:" + TARGET));
        assertTrue(output.contains("runtimeOnly(\"org.hibernate.orm:hibernate-spatial\")"));
        assertTrue(output.contains("runtimeOnly(platform(\"org.hibernate.orm:hibernate-bom:" + TARGET));
    }

    @ParameterizedTest @ValueSource(strings = {"build.gradle", "build.gradle.kts"})
    void sharedResolutionClasspathsProtectRuntimeLibrariesAndPublishedApiNeedsItsOwnCoverage(String path) {
        String input = "dependencies { implementation(platform(\"org.hibernate.orm:hibernate-platform:" + TARGET + "\")); "
                + "runtimeOnly(\"org.hibernate.orm:hibernate-core:" + TARGET + "\") }\n";
        var result = adopt(path, input);
        assertEquals(input, result.files().get(path));
        assertTrue(result.rows().isEmpty());
        input = "dependencies { implementation(platform(\"org.hibernate.orm:hibernate-bom:" + TARGET + "\")); "
                + "api(\"org.hibernate.orm:hibernate-core:" + TARGET + "\") }\n";
        result = adopt(path, input);
        assertEquals(input, result.files().get(path));
        assertFalse(result.rows().isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {"build.gradle", "build.gradle.kts"})
    void conflictingBomOrUnknownInheritanceRetainsInputAndReports(String path) {
        for (String prefix : List.of(
                "implementation(platform(\"org.hibernate.orm:hibernate-bom:8.0.0.Beta2\"))\n",
                "implementation(platform(\"org.hibernate.orm:hibernate-bom:" + TARGET + "\"))\nimplementation(platform(\"org.hibernate.orm:hibernate-bom:" + TARGET + "\"))\n",
                "implementation(platform(\"org.hibernate.orm:hibernate-bom:$unknown\"))\n")) {
            String input = "dependencies { " + prefix + "implementation(\"org.hibernate.orm:hibernate-core:" + TARGET + "\") }\n";
            var result = adopt(path, input);
            assertEquals(input, result.files().get(path));
            assertFalse(result.rows().isEmpty());
        }
        String input = "configurations { testImplementation.extendsFrom(implementation) }\ndependencies { implementation(\"org.hibernate.orm:hibernate-core:" + TARGET + "\") }\n";
        var result = adopt(path, input);
        assertEquals(input, result.files().get(path));
        assertFalse(result.rows().isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {"build.gradle", "build.gradle.kts"})
    void composedMigrationAndCompleteCatalogConsumers(String path) {
        Map<String, String> input = Map.of("settings.gradle", "rootProject.name = 'example'\n", path,
                "dependencies { implementation(libs.core) }\n", "gradle/libs.versions.toml",
                "[versions]\norm = \"7.4.11.Final\"\n[libraries]\ncore = { module = \"org.hibernate:hibernate-core\", version.ref = \"orm\" }\n");
        var result = adopt(input, true);
        assertTrue(result.rows().isEmpty(), result.rows().toString());
        assertTrue(result.files().get(path).contains("org.hibernate.orm:hibernate-bom:" + TARGET));
        assertTrue(result.files().get("gradle/libs.versions.toml").contains("core = { module = \"org.hibernate.orm:hibernate-core\" }"));
        assertTrue(result.files().get("gradle/libs.versions.toml").contains("orm ="));
    }

    @Test void catalogIncompleteOrProtectedConsumersRetainVersion() {
        String catalog = "[libraries]\ncore = { module = \"org.hibernate.orm:hibernate-core\", version = \"" + TARGET + "\" }\n";
        var incomplete = adopt(Map.of("build.gradle", "dependencies { implementation(libs.core) }\n",
                "gradle/libs.versions.toml", catalog), false);
        assertEquals(catalog, incomplete.files().get("gradle/libs.versions.toml"));
        assertFalse(incomplete.rows().isEmpty());
        String script = "dependencies { implementation(platform(\"org.hibernate.orm:hibernate-platform:" + TARGET + "\")); implementation(libs.core) }\n";
        var protectedResult = adopt(Map.of("build.gradle", script, "gradle/libs.versions.toml", catalog), false);
        assertEquals(script, protectedResult.files().get("build.gradle"));
        assertEquals(catalog, protectedResult.files().get("gradle/libs.versions.toml"));
        assertTrue(protectedResult.rows().isEmpty());
    }

    @Test void mavenImportsBeforeOtherBomsAndPreservesDirectAndThirdPartyDeclarations() {
        String thirdParty = "<dependency><groupId>com.example</groupId><artifactId>application-platform</artifactId><version>1.0</version><type>pom</type><scope>import</scope></dependency>";
        String logging = "<dependency><groupId>org.jboss.logging</groupId><artifactId>jboss-logging</artifactId><version>3.6.1.Final</version></dependency>";
        String input = "<project>" + management(thirdParty) + "<dependencies>" + dependency("hibernate-core", TARGET) + logging + "</dependencies></project>";
        var result = adopt("pom.xml", input);
        String output = result.files().get("pom.xml");
        assertTrue(result.rows().isEmpty(), result.rows().toString());
        assertTrue(output.indexOf("<artifactId>hibernate-bom") < output.indexOf("<artifactId>application-platform"));
        assertTrue(output.contains(dependency("hibernate-core", "")));
        assertTrue(output.contains(logging));
        assertTrue(output.contains(thirdParty));
    }

    @Test void mavenExistingRootPlatformProtectsProfilesAndProfilePlatformProtectsOnlyItsScope() {
        String profile = "<profiles><profile><id>extra</id>%s<dependencies>" + dependency("hibernate-envers", TARGET) + "</dependencies></profile></profiles>";
        String input = "<project>" + management(bom("hibernate-platform", TARGET)) + "<dependencies>" + dependency("hibernate-core", TARGET) + "</dependencies>"
                + profile.formatted("") + "</project>";
        assertEquals(input, adopt("pom.xml", input).files().get("pom.xml"));
        input = "<project><dependencies>" + dependency("hibernate-core", TARGET) + "</dependencies>" + profile.formatted(management(bom("hibernate-platform", TARGET))) + "</project>";
        var result = adopt("pom.xml", input);
        assertTrue(result.rows().isEmpty());
        assertTrue(result.files().get("pom.xml").contains(dependency("hibernate-envers", TARGET)));
        assertTrue(result.files().get("pom.xml").contains(dependency("hibernate-core", "")));
    }

    @Test void mavenExistingBomAndInheritedCoverageAreReused() {
        String input = "<project>" + management(bom("hibernate-bom", TARGET)) + "<dependencies>" + dependency("hibernate-core", TARGET)
                + "</dependencies><profiles><profile><id>extra</id><dependencies>" + dependency("hibernate-envers", TARGET) + "</dependencies></profile></profiles></project>";
        var result = adopt("pom.xml", input);
        assertEquals(input.replace(dependency("hibernate-core", TARGET), dependency("hibernate-core", ""))
                .replace(dependency("hibernate-envers", TARGET), dependency("hibernate-envers", "")), result.files().get("pom.xml"));
        assertTrue(result.rows().isEmpty());
        input = "<project><dependencies>" + dependency("hibernate-core", TARGET)
                + "</dependencies><profiles><profile><id>extra</id><dependencies>" + dependency("hibernate-envers", TARGET) + "</dependencies></profile></profiles></project>";
        result = adopt("pom.xml", input);
        assertTrue(result.rows().isEmpty(), result.rows().toString());
        assertEquals(1, result.files().get("pom.xml").split("<artifactId>hibernate-bom</artifactId>", -1).length - 1);
    }

    @Test void mavenParentConflictsAndDirectOverridesRetainVersions() {
        String library = "<dependencies>" + dependency("hibernate-core", TARGET) + "</dependencies>";
        for (String prefix : List.of("<parent><groupId>example</groupId><artifactId>parent</artifactId><version>1</version></parent>",
                management(bom("hibernate-bom", "8.0.0.Beta2")), management(bom("hibernate-bom", TARGET) + bom("hibernate-bom", TARGET)),
                management(dependency("hibernate-core", "8.0.0.Beta2")))) {
            String input = "<project>" + prefix + library + "</project>";
            var result = adopt("pom.xml", input);
            assertEquals(input, result.files().get("pom.xml"));
            assertFalse(result.rows().isEmpty());
        }
        String direct = management(dependency("hibernate-core", TARGET));
        var result = adopt("pom.xml", "<project>" + direct + library + "</project>");
        assertTrue(result.rows().isEmpty());
        assertTrue(result.files().get("pom.xml").contains(dependency("hibernate-core", TARGET)));
    }

    @ParameterizedTest @ValueSource(strings = {"build.gradle", "build.gradle.kts"})
    void richVersionAndCustomArtifactDependenciesAreRetained(String path) {
        for (String customization : List.of("version { strictly(\"7.4.11.Final\") }", "artifact { classifier = \"tests\" }")) {
            String input = "dependencies { implementation(\"org.hibernate.orm:hibernate-core:" + TARGET + "\") { " + customization + " } }\n";
            var result = adopt(path, input);
            assertEquals(input, result.files().get(path));
            assertFalse(result.rows().isEmpty());
        }
    }

    @Test void mavenProfilePropertiesDoNotMasqueradeAsRootManagement() {
        String input = "<project><properties><orm.version>" + TARGET + "</orm.version></properties>"
                + management(dependency("hibernate-core", "${orm.version}"))
                + "<dependencies>" + dependency("hibernate-core", TARGET) + "</dependencies>"
                + "<profiles><profile><id>older</id><properties><orm.version>8.0.0.Beta2</orm.version></properties>"
                + "<dependencies>" + dependency("hibernate-core", "${orm.version}") + "</dependencies></profile></profiles></project>";
        var result = adopt("pom.xml", input);
        assertEquals(input, result.files().get("pom.xml"));
        assertFalse(result.rows().isEmpty());
        input = "<project><profiles><profile><id>local</id><properties><orm.version>" + TARGET + "</orm.version></properties>"
                + "<dependencies>" + dependency("hibernate-core", "${orm.version}") + "</dependencies></profile></profiles></project>";
        result = adopt("pom.xml", input);
        assertTrue(result.rows().isEmpty(), result.rows().toString());
        assertTrue(result.files().get("pom.xml").contains(dependency("hibernate-core", "")));
    }

    @Test void prefixedMavenXmlPreservesNamespaceAndUnsupportedDependencyTypes() {
        String ordinary = dependency("hibernate-core", TARGET);
        String classified = dependency("hibernate-envers", TARGET).replace("</dependency>", "<classifier>tests</classifier></dependency>");
        String input = ("<project><dependencies>" + ordinary + classified + "</dependencies></project>")
                .replace("<", "<p:").replace("<p:/", "</p:").replace("<p:project>", "<p:project xmlns:p='http://maven.apache.org/POM/4.0.0'>");
        var result = adopt("pom.xml", input);
        String output = result.files().get("pom.xml");
        assertTrue(output.contains("<p:artifactId>hibernate-bom</p:artifactId>"), output);
        assertTrue(output.contains("<p:artifactId>hibernate-core</p:artifactId></p:dependency>"), output);
        assertTrue(output.contains("<p:artifactId>hibernate-envers</p:artifactId><p:version>" + TARGET), output);
        assertFalse(result.rows().isEmpty());
    }

    @Test void unresolvedHibernateManagementIdentityRetainsInput() {
        String input = "<project>" + management(bom("hibernate-platform", TARGET).replace("<groupId>org.hibernate.orm</groupId>", "<groupId>${unknown}</groupId>"))
                + "<dependencies>" + dependency("hibernate-core", TARGET) + "</dependencies></project>";
        var result = adopt("pom.xml", input);
        assertEquals(input, result.files().get("pom.xml"));
        assertFalse(result.rows().isEmpty());
    }

    @Test void unresolvedDirectManagementCannotBeAssumedUnrelated() {
        for (String unresolved : List.of(dependency("hibernate-core", "8.0.0.Beta2").replace("org.hibernate.orm", "${unknown.group}"),
                dependency("${unknown.artifact}", "8.0.0.Beta2"))) {
            String input = "<project>" + management(unresolved) + "<dependencies>" + dependency("hibernate-core", TARGET) + "</dependencies></project>";
            var result = adopt("pom.xml", input);
            assertEquals(input, result.files().get("pom.xml"));
            assertFalse(result.rows().isEmpty());
        }
        String input = "dependencies { implementation(\"$unknownGroup:hibernate-core:" + TARGET + "\") }\n";
        var result = adopt("build.gradle", input);
        assertEquals(input, result.files().get("build.gradle"));
        assertFalse(result.rows().isEmpty());
    }

    @Test void unsupportedCatalogFieldsAreRetained() {
        String catalog = "[libraries]\ncore = { module = \"org.hibernate.orm:hibernate-core\", version = \"" + TARGET + "\", custom = \"unknown\" }\n";
        Map<String, String> input = Map.of("settings.gradle", "rootProject.name = 'example'\n",
                "build.gradle", "dependencies { implementation(libs.core) }\n", "gradle/libs.versions.toml", catalog);
        var result = adopt(input, false);
        assertEquals(input, result.files());
        assertFalse(result.rows().isEmpty());
    }

    @Test void missingMetadataRetainsInputAndReports(@TempDir Path repository) {
        String input = "dependencies { implementation(\"org.hibernate.orm:hibernate-core:" + TARGET + "\") }\n";
        var ctx = new InMemoryExecutionContext(e -> { throw new AssertionError(e); });
        MavenExecutionContextView.view(ctx).setMirrors(List.of(new MavenRepositoryMirror("empty", repository.toUri().toString(), "*", true, false, null)))
                .setAddLocalRepository(false).setAddCentralRepository(false);
        var run = new AdoptHibernateBom(TARGET).run(new InMemoryLargeSourceSet(List.of(parse("build.gradle", input, ctx))), ctx);
        assertTrue(run.getChangeset().getAllResults().isEmpty());
        assertFalse(run.getDataTableRows(SkippedMigrations.class).isEmpty());
    }
}
