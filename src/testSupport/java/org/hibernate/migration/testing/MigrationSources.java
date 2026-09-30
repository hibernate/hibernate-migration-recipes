package org.hibernate.migration.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/// Reads compiled migration inputs while preserving source-root-relative paths.
///
/// @author Steve Ebersole
public final class MigrationSources {
    private final Path root;

    public MigrationSources(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public static MigrationSources configured() {
        String path = System.getProperty("migration.sources");
        if (path == null || path.isBlank()) {
            throw new IllegalStateException("Missing migration.sources source root");
        }
        return new MigrationSources(Path.of(path));
    }

    public String read(String relativePath) {
        try {
            return Files.readString(resolve(relativePath));
        }
        catch (IOException e) {
            throw new UncheckedIOException("Cannot read migration source " + relativePath, e);
        }
    }

    public Map<String, String> files(String... relativePaths) {
        Map<String, String> sources = new LinkedHashMap<>();
        for (String path : relativePaths) {
            sources.put(key(resolve(path)), read(path));
        }
        return sources;
    }

    public Map<String, String> scenario(String relativeDirectory) {
        Map<String, String> sources = new LinkedHashMap<>();
        try (var paths = Files.walk(resolve(relativeDirectory))) {
            paths.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java"))
                    .sorted().forEach(path -> sources.put(key(path), read(key(path))));
        }
        catch (IOException e) {
            throw new UncheckedIOException("Cannot read migration scenario " + relativeDirectory, e);
        }
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("No Java sources in migration scenario " + relativeDirectory);
        }
        return sources;
    }

    private Path resolve(String relativePath) {
        Path path = Path.of(relativePath);
        Path resolved = root.resolve(path).normalize();
        if (path.isAbsolute() || !resolved.startsWith(root)) {
            throw new IllegalArgumentException("Migration source path must be relative to its root: " + relativePath);
        }
        return resolved;
    }

    private String key(Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }
}
