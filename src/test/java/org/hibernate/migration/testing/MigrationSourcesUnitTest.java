package org.hibernate.migration.testing;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Checks complete scenario loading, stable paths, and failures for missing fixture inputs.
/// @author Steve Ebersole
class MigrationSourcesUnitTest {
    @TempDir Path root;

    @Test
    void scenarioPreservesRelativePathsAndIgnoresNonJavaFiles() throws Exception {
        Files.createDirectories(root.resolve("scenario/nested"));
        Files.writeString(root.resolve("scenario/Example.java"), "first source\n");
        Files.writeString(root.resolve("scenario/nested/Example.java"), "second source\n");
        Files.writeString(root.resolve("scenario/notes.txt"), "not Java");
        var reader = new MigrationSources(root);
        var files = reader.scenario("scenario");
        assertEquals(List.of("scenario/Example.java", "scenario/nested/Example.java"), List.copyOf(files.keySet()));
        assertEquals("first source\n", files.get("scenario/Example.java"));
        assertEquals("second source\n", files.get("scenario/nested/Example.java"));
        assertEquals(files, reader.files("scenario/Example.java", "scenario/nested/Example.java"));
    }

    @Test
    void missingOrEmptyInputsFailRatherThanSilentlySkippingValidation() throws Exception {
        var reader = new MigrationSources(root);
        assertThrows(UncheckedIOException.class, () -> reader.read("Missing.java"));
        assertThrows(UncheckedIOException.class, () -> reader.scenario("missing"));
        Files.createDirectory(root.resolve("empty"));
        assertThrows(IllegalArgumentException.class, () -> reader.scenario("empty"));
    }

    @Test
    void pathsMustStayRelativeToTheSourceRoot() {
        var reader = new MigrationSources(root);
        assertThrows(IllegalArgumentException.class, () -> reader.read("../Outside.java"));
        assertThrows(IllegalArgumentException.class, () -> reader.read(root.resolve("Example.java").toString()));
    }
}
