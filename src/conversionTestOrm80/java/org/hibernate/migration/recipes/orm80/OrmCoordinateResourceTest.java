package org.hibernate.migration.recipes.orm80;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/// Verifies that packaging follows the configured target and reacts to incremental version changes.
///
/// @author Steve Ebersole
class OrmCoordinateResourceTest {
    @TempDir Path project;
    @Test void configuredTargetRegeneratesPackagedYamlWithoutOrmResolution() throws Exception {
        Path repository = Path.of(System.getProperty("user.dir"));
        copy(repository.resolve("build.gradle.kts"), project.resolve("build.gradle.kts"));
        copy(repository.resolve("settings.gradle.kts"), project.resolve("settings.gradle.kts"));
        copy(repository.resolve("buildSrc/build.gradle.kts"), project.resolve("buildSrc/build.gradle.kts"));
        copy(repository.resolve("buildSrc/src/main/kotlin/MigrationTestingPlugin.kt"), project.resolve("buildSrc/src/main/kotlin/MigrationTestingPlugin.kt"));
        copy(repository.resolve("src/main/resources/META-INF/rewrite/orm80.yml"), project.resolve("src/main/resources/META-INF/rewrite/orm80.yml"));
        copy(repository.resolve("src/main/resources/META-INF/rewrite/platform-version.properties"), project.resolve("src/main/resources/META-INF/rewrite/platform-version.properties"));
        Path build = project.resolve("build.gradle.kts");
        String initial = Files.readString(build);
        // Only resource processing runs; neither version needs to be resolved for substitution.
        String configured = OrmCoordinatesTest.TARGET;
        processResources();
        Path yaml = project.resolve("build/resources/main/META-INF/rewrite/orm80.yml");
        assertTrue(Files.readString(yaml).contains("targetVersion: '" + configured + "'"));
        Files.writeString(build, initial.replace("ormVersion.set(\"" + configured + "\")", "ormVersion.set(\"8.0.123.Beta42\")"));
        processResources();
        assertTrue(Files.readString(yaml).contains("targetVersion: '8.0.123.Beta42'"));
        assertFalse(Files.readString(yaml).contains("@orm80TargetVersion@"));
        assertFalse(Files.exists(project.resolve("build/migration-testing/environments.properties")));
    }
    private void processResources() throws Exception {
        Path log = project.resolve("resource-processing.log");
        Process process = new ProcessBuilder(List.of(System.getProperty("coordinate.gradleExecutable"), "--daemon", "--no-configuration-cache",
                "-Dorg.gradle.jvmargs=-Xmx384m", "processResources")).directory(project.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(180, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Resource build timed out: " + log); }
        assertEquals(0, process.exitValue(), Files.readString(log));
    }
    private static void copy(Path source, Path destination) throws Exception {
        Files.createDirectories(destination.getParent()); Files.copy(source, destination);
    }
}
