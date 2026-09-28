/* SPDX-License-Identifier: Apache-2.0 */
package org.hibernate.migration.testing;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/// Reads build-resolved dependency environments without loading either provider's classes.
/// @author Steve Ebersole
public final class ValidationEnvironment {
    private final Properties metadata;
    public ValidationEnvironment(Properties metadata) { this.metadata = metadata; }
    public static ValidationEnvironment configured() {
        Properties values = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(Objects.requireNonNull(System.getProperty("migration.metadata"), "migration.metadata")))) {
            values.load(reader);
        }
        catch (IOException e) { throw new UncheckedIOException(e); }
        return new ValidationEnvironment(values);
    }
    public String value(String key) {
        String value = metadata.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing environment metadata: " + key);
        return value;
    }
    public String source(String migration) { return value("migration." + migration + ".source"); }
    public String target(String migration) { return value("migration." + migration + ".target"); }
    public List<Path> paths(String profile) {
        if (!Arrays.asList(value("profiles").split(",")).contains(profile)) throw new IllegalArgumentException("Unknown validation profile: " + profile);
        List<Path> paths = Arrays.stream(value(profile + ".compile").split(java.util.regex.Pattern.quote(File.pathSeparator))).map(Path::of).toList();
        for (Path path : paths) if (!Files.isRegularFile(path)) throw new IllegalStateException("Missing artifact for " + profile + ": " + path);
        verifyJar(paths, "jakarta.persistence-api", value(profile + ".jpaVersion"));
        if (metadata.containsKey(profile + ".ormVersion")) verifyJar(paths, "hibernate-core", value(profile + ".ormVersion"));
        return paths;
    }
    private static void verifyJar(List<Path> paths, String artifact, String version) {
        List<Path> matching = paths.stream().filter(p -> p.getFileName().toString().startsWith(artifact + "-")).toList();
        if (matching.size() != 1 || !matching.getFirst().getFileName().toString().equals(artifact + "-" + version + ".jar")) throw new IllegalStateException("Wrong " + artifact + " artifacts: " + matching);
    }
    public Context context(String input) {
        String migration = Objects.requireNonNull(System.getProperty("migration.id"), "migration.id");
        return new Context(migration, input, target(migration));
    }
    public Context primary() {
        String migration = Objects.requireNonNull(System.getProperty("migration.id"), "migration.id");
        return new Context(migration, source(migration), target(migration));
    }
    public record Context(String migration, String input, String target) {}
}
