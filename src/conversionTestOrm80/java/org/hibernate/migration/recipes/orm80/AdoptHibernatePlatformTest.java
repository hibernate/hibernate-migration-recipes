package org.hibernate.migration.recipes.orm80;

import org.hibernate.migration.recipes.platform.AdoptHibernatePlatform;
import org.hibernate.migration.recipes.table.SkippedMigrations;
import org.hibernate.migration.testing.RecipeExecutionContexts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openrewrite.*;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import java.nio.file.Path;
import java.util.*;
import static org.hibernate.migration.recipes.orm80.OrmCoordinatesTest.*;
import static org.junit.jupiter.api.Assertions.*;

/// Verifies platform BOM injection and version omission, both standalone and composed with coordinate migration.
///
/// @author Steve Ebersole
class AdoptHibernatePlatformTest {

    // ── Helpers ──

    static Outcome runPlatformAlone(Map<String, String> files) {
        var ctx = RecipeExecutionContexts.standard(e -> { throw new AssertionError(e); });
        List<SourceFile> sources = files.entrySet().stream().map(e -> parse(e.getKey(), e.getValue(), ctx)).toList();
        var result = new AdoptHibernatePlatform(TARGET).run(new InMemoryLargeSourceSet(sources), ctx);
        Map<String, String> output = new LinkedHashMap<>(files);
        for (Result r : result.getChangeset().getAllResults()) output.put(r.getAfter().getSourcePath().toString(), r.getAfter().printAll());
        return new Outcome(output, result.getDataTableRows(SkippedMigrations.class));
    }
    static Outcome runPlatformAlone(String path, String text) { return runPlatformAlone(Map.of(path, text)); }

    static Outcome runWithPlatform(Map<String, String> files) {
        var ctx = RecipeExecutionContexts.standard(e -> { throw new AssertionError(e); });
        List<SourceFile> sources = files.entrySet().stream().map(e -> parse(e.getKey(), e.getValue(), ctx)).toList();
        Recipe composed = new Recipe() {
            @Override public @org.jspecify.annotations.NonNull String getDisplayName() { return "test"; }
            @Override public @org.jspecify.annotations.NonNull String getDescription() { return "test"; }
            @Override public @org.jspecify.annotations.NonNull List<Recipe> getRecipeList() {
                return List.of(new MigrateOrmCoordinates(TARGET), new AdoptHibernatePlatform(TARGET));
            }
        };
        var result = composed.run(new InMemoryLargeSourceSet(sources), ctx);
        Map<String, String> output = new LinkedHashMap<>(files);
        for (Result r : result.getChangeset().getAllResults()) output.put(r.getAfter().getSourcePath().toString(), r.getAfter().printAll());
        return new Outcome(output, result.getDataTableRows(SkippedMigrations.class));
    }
    static Outcome runWithPlatform(String path, String text) { return runWithPlatform(Map.of(path, text)); }

    // ── Standalone: platform recipe only ──

    @ParameterizedTest @ValueSource(strings = {"build.gradle", "build.gradle.kts"})
    void platformAloneInjectsAndOmitsVersions(String path) {
        String input = "dependencies {\n    implementation(\"org.hibernate.orm:hibernate-core:" + TARGET + "\")\n}\n";
        var result = runPlatformAlone(path, input);
        String output = result.files().get(path);
        assertTrue(output.contains("implementation(platform("), output);
        assertTrue(output.contains("implementation(\"org.hibernate.orm:hibernate-core\")"), output);
    }
    @Test void platformAloneMavenInjectsBom() {
        String input = "<project><dependencies><dependency><groupId>org.hibernate.orm</groupId><artifactId>hibernate-core</artifactId><version>" + TARGET + "</version></dependency></dependencies></project>";
        var result = runPlatformAlone("pom.xml", input);
        String output = result.files().get("pom.xml");
        assertTrue(output.contains("<artifactId>hibernate-platform</artifactId>"), output);
        assertTrue(output.contains("<type>pom</type><scope>import</scope>"), output);
        assertTrue(output.contains("<artifactId>hibernate-core</artifactId></dependency>"), output);
    }
    @ParameterizedTest @ValueSource(strings = {"build.gradle", "build.gradle.kts"})
    void platformAloneExistingPlatformIsPreserved(String path) {
        String input = "dependencies {\n    implementation(enforcedPlatform(\"org.hibernate.orm:hibernate-platform:" + TARGET + "\"))\n    implementation(\"org.hibernate.orm:hibernate-core:" + TARGET + "\")\n}\n";
        var result = runPlatformAlone(path, input);
        if (result.files().get(path).equals(input)) return;
        String output = result.files().get(path);
        assertEquals(1, output.split("hibernate-platform", -1).length - 1, output);
    }
    @Test void platformAloneMavenExistingPlatformIsPreserved() {
        String input = "<project><dependencyManagement><dependencies><dependency><groupId>org.hibernate.orm</groupId><artifactId>hibernate-platform</artifactId><version>" + TARGET + "</version><type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement><dependencies><dependency><groupId>org.hibernate.orm</groupId><artifactId>hibernate-core</artifactId><version>" + TARGET + "</version></dependency></dependencies></project>";
        var result = runPlatformAlone("pom.xml", input);
        assertEquals(input, result.files().get("pom.xml"));
    }
    @Test void platformAloneIgnoresNonOrmDependencies() {
        String input = "<project><dependencies><dependency><groupId>org.hibernate.validator</groupId><artifactId>hibernate-validator</artifactId><version>8.0.0.Final</version></dependency></dependencies></project>";
        var result = runPlatformAlone("pom.xml", input);
        assertEquals(input, result.files().get("pom.xml"));
    }
    @ParameterizedTest @ValueSource(strings = {"build.gradle", "build.gradle.kts"})
    void platformAloneToolingRetainsVersion(String path) {
        String input = "dependencies {\n    implementation(\"org.hibernate.orm:hibernate-core:" + TARGET + "\")\n    annotationProcessor(\"org.hibernate.orm:hibernate-processor:" + TARGET + "\")\n}\n";
        var result = runPlatformAlone(path, input);
        String output = result.files().get(path);
        assertTrue(output.contains("hibernate-processor:" + TARGET), output);
        assertTrue(output.contains("implementation(\"org.hibernate.orm:hibernate-core\")"), output);
        assertTrue(output.contains("implementation(platform("), output);
    }
    @Test void platformAloneMavenWithParentSkips() {
        String input = "<project><parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent><dependencies><dependency><groupId>org.hibernate.orm</groupId><artifactId>hibernate-core</artifactId><version>" + TARGET + "</version></dependency></dependencies></project>";
        var result = runPlatformAlone("pom.xml", input);
        assertEquals(input, result.files().get("pom.xml"));
    }

    // ── Composed: migration + platform ──

    @ParameterizedTest @ValueSource(strings = {"build.gradle", "build.gradle.kts"})
    void gradleLibrariesProcessorsAndPlatform(String path) {
        String input = "dependencies {\n    implementation(\"org.hibernate:hibernate-core:7.4.11.Final\")\n    annotationProcessor(\"org.hibernate.orm:hibernate-jpamodelgen:7.4.11.Final\")\n}\n";
        String expected = "dependencies {\n    implementation(\"org.hibernate.orm:hibernate-core\")\n    annotationProcessor(\"org.hibernate.orm:hibernate-processor:" + TARGET + "\")\n    implementation(platform(\"org.hibernate.orm:hibernate-platform:" + TARGET + "\"))\n}\n";
        assertEquals(expected, runWithPlatform(path, input).files().get(path));
    }
    @Test void mavenBomPluginAndProcessorWithPlatform() {
        String input = "<project><dependencies><dependency><groupId>org.hibernate</groupId><artifactId>hibernate-core</artifactId><version>7.4.11.Final</version></dependency></dependencies><build><plugins><plugin><groupId>org.hibernate.orm.tooling</groupId><artifactId>hibernate-enhance-maven-plugin</artifactId><version>7.4.11.Final</version><configuration><keep>true</keep></configuration></plugin></plugins></build></project>";
        String output = runWithPlatform("pom.xml", input).files().get("pom.xml");
        assertTrue(output.contains("<artifactId>hibernate-core</artifactId></dependency>"), output);
        assertTrue(output.contains("<artifactId>hibernate-maven-plugin</artifactId><version>" + TARGET + "</version>"), output);
        assertTrue(output.contains("<type>pom</type><scope>import</scope>"), output);
        assertTrue(output.contains("<configuration><keep>true</keep></configuration>"));
    }
    @ParameterizedTest @ValueSource(strings = {"build.gradle", "build.gradle.kts"})
    void catalogCompleteLocalConsumersOmitVersionsWithPlatform(String path) {
        var output = runWithPlatform(Map.of("settings.gradle", "rootProject.name = 'example'\n", path,
                "dependencies { implementation(libs.core) }\n", "gradle/libs.versions.toml",
                "[libraries]\ncore = { module = \"org.hibernate:hibernate-core\", version = \"7.4.11.Final\" }\n")).files();
        assertEquals("[libraries]\ncore = { module = \"org.hibernate.orm:hibernate-core\" }\n", output.get("gradle/libs.versions.toml"));
        assertTrue(output.get(path).contains("implementation(platform("));
    }
    @ParameterizedTest @ValueSource(strings = {"build.gradle", "build.gradle.kts"})
    void namedNotationAndTrailingClosureWithPlatform(String path) {
        String declaration = path.endsWith(".kts") ? "implementation(group = \"org.hibernate\", name = \"hibernate-core\", version = \"7.4.11.Final\")"
                : "implementation(group: 'org.hibernate', name: 'hibernate-core', version: '7.4.11.Final')";
        String input = "dependencies {\n    " + declaration + " { transitive = false }\n}\n";
        String output = runWithPlatform(path, input).files().get(path);
        assertTrue(output.contains("org.hibernate.orm"), output); assertFalse(output.contains("7.4.11.Final"), output);
        assertFalse(output.contains("version =") || output.contains("version:"), output);
        assertTrue(output.contains("transitive = false"), output);
    }
    @Test void groovyNamedNotationVariantsAndMapLiteralWithPlatform() {
        for (String call : List.of("implementation([group: 'org.hibernate', name: 'hibernate-core', version: '7.4.11.Final'])",
                "implementation group: 'org.hibernate', name: 'hibernate-core', version: '7.4.11.Final'")) {
            String output = runWithPlatform("build.gradle", "dependencies { " + call + " }\n").files().get("build.gradle");
            assertTrue(output.contains("group: 'org.hibernate.orm'"), output);
            assertFalse(output.contains("version:"), output);
            assertTrue(output.contains("implementation(platform("), output);
        }
    }
    @Test void mavenProfileWithClassifierAndDependencyManagementWithPlatform() {
        String input = "<project><profiles><profile><id>extra</id><dependencies><dependency><groupId>org.hibernate</groupId><artifactId>hibernate-core</artifactId><version>7.4.11.Final</version><classifier>tests</classifier></dependency></dependencies></profile></profiles></project>";
        String output = runWithPlatform("pom.xml", input).files().get("pom.xml");
        assertTrue(output.contains("<artifactId>hibernate-core</artifactId><version>" + TARGET + "</version><classifier>tests</classifier>"), output);
        assertTrue(output.contains("</dependencies><dependencyManagement>"), output);
        assertTrue(output.contains("</dependencyManagement></profile>"), output);
    }
    @Test void unavailablePlatformRetainsExplicitVersion(@org.junit.jupiter.api.io.TempDir Path emptyRepository) {
        String input = "dependencies { implementation(\"org.hibernate:hibernate-core:7.4.11.Final\") }\n";
        Outcome ordinary = runWithPlatform("build.gradle", input);
        assertTrue(ordinary.files().get("build.gradle").contains("implementation(platform("));
        assertFalse(ordinary.files().get("build.gradle").contains("7.4.11.Final"));
        var ctx = new InMemoryExecutionContext(e -> { throw new AssertionError(e); });
        org.openrewrite.maven.MavenExecutionContextView.view(ctx)
                .setMirrors(List.of(new org.openrewrite.maven.tree.MavenRepositoryMirror("empty", emptyRepository.toUri().toString(), "*", true, false, null)))
                .setAddLocalRepository(false).setAddCentralRepository(false);
        SourceFile source = parse("build.gradle", input, ctx);
        Recipe composed = new Recipe() {
            @Override public @org.jspecify.annotations.NonNull String getDisplayName() { return "test"; }
            @Override public @org.jspecify.annotations.NonNull String getDescription() { return "test"; }
            @Override public @org.jspecify.annotations.NonNull List<Recipe> getRecipeList() {
                return List.of(new MigrateOrmCoordinates(TARGET), new AdoptHibernatePlatform(TARGET));
            }
        };
        var result = composed.run(new InMemoryLargeSourceSet(List.of(source)), ctx);
        String output = result.getChangeset().getAllResults().get(0).getAfter().printAll();
        assertTrue(output.contains("org.hibernate.orm:hibernate-core:" + TARGET), output);
        assertFalse(output.contains("platform("), output);
    }
    @ParameterizedTest @ValueSource(strings = {"build.gradle", "build.gradle.kts"})
    void gradleExistingPlatformAndPluginWithPlatform(String path) {
        String input = "plugins { id(\"org.hibernate.orm\") version \"7.4.11.Final\" apply false }\ndependencies {\n    implementation(enforcedPlatform(\"org.hibernate.orm:hibernate-platform:7.4.11.Final\"))\n    implementation(\"org.hibernate:hibernate-core:7.4.11.Final\")\n}\n";
        String output = runWithPlatform(path, input).files().get(path);
        assertTrue(output.contains("version \"" + TARGET + "\" apply false"), output);
        assertTrue(output.contains("enforcedPlatform(\"org.hibernate.orm:hibernate-platform:" + TARGET + "\")"), output);
        assertTrue(output.contains("implementation(\"org.hibernate.orm:hibernate-core\")"), output);
    }
}
