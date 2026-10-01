package org.hibernate.migration.recipes.orm80;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/// Executes the build tools against migrated fixture files and inspects their resolved output.
///
/// @author Steve Ebersole
class OrmCoordinateResolutionTest {
    private Path root() { return Path.of(System.getProperty("coordinate.fixtures")); }
    private Path work(Path directory) throws Exception {
        Path work = Path.of(System.getProperty("coordinate.workDirectory")).resolve(root().relativize(directory));
        return Files.createDirectories(work);
    }
    private String target() throws Exception { return Files.readString(root().resolve("target-version.txt")).trim(); }
    private String execute(Path directory, List<String> command) throws Exception {
        Path log = work(directory).resolve("resolution.log");
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(240, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Resolver timed out; " + log); }
        String output = Files.readString(log);
        assertEquals(0, process.exitValue(), output); return output;
    }
    @ParameterizedTest @ValueSource(strings = {"groovy", "kotlin", "catalog-groovy", "catalog-kotlin"})
    void gradle(String fixture) throws Exception {
        Path directory = root().resolve(fixture);
        String output = execute(directory, gradle(directory));
        for (String artifact : List.of("hibernate-core", "hibernate-envers", "hibernate-processor"))
            assertTrue(output.contains("SELECTED:org.hibernate.orm:" + artifact + ":" + target()), output);
        assertFalse(output.contains("SELECTED:org.hibernate:"), output);
        assertFalse(output.contains("hibernate-entitymanager"), output);
        assertTrue(output.contains("SELECTED:org.jboss.logging:jboss-logging:"), output);
        if (fixture.startsWith("catalog")) {
            String catalog = Files.readString(directory.resolve("gradle/libs.versions.toml"));
            assertTrue(catalog.contains("logging = { module = \"org.jboss.logging:jboss-logging\", version = \"3.6.1.Final\" }"), catalog);
            assertTrue(catalog.contains("orm = [\"entity\", \"core\", \"envers\", \"logging\"]"), catalog);
        }
    }
    @Test void ordinaryPlatformRespectsExistingForce() throws Exception {
        Path directory = root().resolve("forced-groovy");
        String output = execute(directory, gradle(directory));
        String source = Files.readString(root().resolve("source-version.txt")).trim();
        assertTrue(output.contains("SELECTED:org.hibernate.orm:hibernate-core:" + source), output);
        assertTrue(output.contains("SELECTED:org.hibernate.orm:hibernate-envers:" + target()), output);
    }
    @Test void maven() throws Exception {
        Path directory = root().resolve("maven");
        Path work = work(directory);
        String executable = Path.of(System.getProperty("coordinate.mavenHome"), "bin", "mvn").toString();
        // Sync does not promise executable mode; invoke the pinned Maven launch script through sh.
        String output = execute(directory, List.of("sh", executable, "-B", "-ntp", "-Dmaven.repo.local=" + root().resolveSibling("resolver-cache/maven"),
                "-Dcoordinate.workDirectory=" + work,
                "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:resolve", "org.apache.maven.plugins:maven-help-plugin:3.5.1:effective-pom", "-Doutput=" + work.resolve("effective-pom.xml")));
        for (String artifact : List.of("hibernate-core", "hibernate-envers"))
            assertTrue(output.contains("org.hibernate.orm:" + artifact + ":jar:" + target()), output);
        assertFalse(output.contains("org.hibernate:hibernate-core:jar:"), output);
        assertFalse(output.contains("hibernate-entitymanager"), output);
        String effective = Files.readString(work.resolve("effective-pom.xml"));
        assertTrue(effective.contains("<artifactId>hibernate-processor</artifactId>"));
        assertTrue(effective.contains("<artifactId>hibernate-maven-plugin</artifactId>"));
        String tooling = execute(directory, List.of("sh", executable, "-B", "-ntp", "-Dmaven.repo.local=" + root().resolveSibling("resolver-cache/maven"),
                "-Dcoordinate.workDirectory=" + work,
                "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get", "-Dartifact=org.hibernate.orm:hibernate-processor:" + target(),
                "-Dtransitive=false", "org.hibernate.orm:hibernate-maven-plugin:" + target() + ":enhance"));
        assertTrue(tooling.contains("BUILD SUCCESS"), tooling);
    }
    @Test void ivy() throws Exception {
        Path directory = root().resolve("ivy");
        execute(directory, ant(directory));
        List<Path> reports;
        try (var paths = Files.list(work(directory).resolve("report"))) { reports = paths.filter(p -> p.toString().endsWith(".xml")).toList(); }
        assertFalse(reports.isEmpty());
        String report = Files.readString(reports.get(0));
        for (String artifact : List.of("hibernate-core", "hibernate-envers", "hibernate-processor")) {
            assertTrue(report.contains("name=\"" + artifact + "\"") && report.contains("organisation=\"org.hibernate.orm\""), report);
        }
        assertTrue(report.contains("name=\"" + target() + "\""), report);
        assertFalse(report.contains("organisation=\"org.hibernate\""), report);
        assertFalse(report.contains("hibernate-entitymanager"), report);
    }
    @Test void mavenAntTasks() throws Exception {
        Path directory = root().resolve("ant");
        String output = execute(directory, ant(directory));
        for (String artifact : List.of("hibernate-core", "hibernate-envers", "hibernate-processor"))
            assertTrue(output.contains(artifact + "-" + target() + ".jar"), output);
        assertFalse(output.contains("/org/hibernate/hibernate-core/"), output);
        assertFalse(output.contains("hibernate-entitymanager"), output);
    }
    private List<String> gradle(Path directory) throws Exception {
        Path work = work(directory);
        return List.of(System.getProperty("coordinate.gradleExecutable"), "--daemon", "--no-configuration-cache",
                "-Dorg.gradle.jvmargs=-Xmx384m", "--project-cache-dir", work.resolve("gradle-cache").toString(),
                "-Pcoordinate.workDirectory=" + work.resolve("build"), "verifyCoordinates");
    }
    private List<String> ant(Path directory) throws Exception {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("coordinate.antClasspath"),
                "org.apache.tools.ant.Main", "-Dcoordinate.workDirectory=" + work(directory),
                "-Divy.default.ivy.user.dir=" + root().resolveSibling("resolver-cache/ivy"), "-Dmaven.repo.local=" + root().resolveSibling("resolver-cache/ant"), "resolve");
    }
}
