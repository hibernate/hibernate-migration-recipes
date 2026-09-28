/* SPDX-License-Identifier: Apache-2.0 */
package org.hibernate.migration.testing;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;

/// Publishes complete converted fixture sets and rejects incomplete or stale handoffs.
/// @author Steve Ebersole
public final class FixtureBundle {
    private FixtureBundle() {}
    public record Entry(String fixture, String variant, String recipe, String scenario, List<String> sources) {
        public String id() { return variant + "/" + fixture; }
    }
    public static List<Entry> catalog(String migration) {
        try (InputStream stream = FixtureBundle.class.getResourceAsStream("/" + migration + "-fixtures.properties")) {
            if (stream == null) throw new IllegalArgumentException("Missing fixture catalog for " + migration);
            return catalog(read(new InputStreamReader(stream, StandardCharsets.UTF_8)));
        }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }
    public static List<Entry> catalog(Properties properties) {
        List<Entry> entries = new ArrayList<>();
        for (String fixture : list(properties, "fixtures")) {
            safeId(fixture);
            for (String variant : list(properties, fixture + ".variants")) {
                safeId(variant);
                List<String> sources = list(properties, fixture + ".sources");
                sources.forEach(path -> safeRelative(Path.of("."), path));
                entries.add(new Entry(fixture, variant, required(properties, fixture + ".recipe"), required(properties, fixture + ".scenario"), sources));
            }
        }
        if (entries.stream().map(Entry::id).distinct().count() != entries.size()) throw new IllegalArgumentException("Duplicate fixture variant");
        return List.copyOf(entries);
    }
    private static void safeId(String id) {
        if (!id.matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("Unsafe fixture identifier: " + id);
    }
    public static Path safeRelative(Path root, String relative) {
        Path path = Path.of(relative);
        if (path.isAbsolute() || relative.contains("\\") || path.normalize().startsWith("..") || !path.normalize().toString().replace('\\', '/').equals(relative)) throw new IllegalArgumentException("Unsafe fixture path: " + relative);
        return root.resolve(path);
    }
    private static String required(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing fixture property: " + key);
        return value;
    }
    private static List<String> list(Properties p, String key) {
        List<String> values = List.of(required(p, key).split(",", -1));
        if (values.stream().anyMatch(String::isBlank) || values.stream().distinct().count() != values.size()) throw new IllegalArgumentException("Invalid fixture list: " + key);
        return values;
    }
    private static Properties read(Reader reader) throws IOException {
        Properties p = new Properties() {
            @Override public Object put(Object key, Object value) {
                if (containsKey(key)) throw new IllegalArgumentException("Duplicate manifest key: " + key);
                return super.put(key, value);
            }
        };
        p.load(reader);
        return p;
    }
    private static Properties expected(String migration, String target, List<Entry> entries, ValidationEnvironment environments) {
        Properties p = new Properties();
        p.setProperty("format", "1");
        p.setProperty("migration", migration);
        p.setProperty("target", target);
        p.setProperty("target.modules", environments.value(target + ".modules"));
        p.setProperty("entries", Integer.toString(entries.size()));
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            String prefix = "entry." + i + ".";
            p.setProperty(prefix + "fixture", e.fixture());
            p.setProperty(prefix + "input", e.variant());
            p.setProperty(prefix + "input.modules", environments.value(e.variant() + ".modules"));
            p.setProperty(prefix + "recipe", e.recipe());
            p.setProperty(prefix + "scenario", e.scenario());
            p.setProperty(prefix + "sources", String.join(",", e.sources()));
        }
        return p;
    }
    public static void publish(Path root, String migration, String target, List<Entry> entries, ValidationEnvironment environments,
                               Function<Entry, Map<String, String>> convert) throws IOException {
        // The caller supplies the dedicated migration output directory, never a source directory.
        if (Files.exists(root)) try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
        Files.createDirectories(root);
        Properties manifest = expected(migration, target, entries, environments);
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            Map<String, String> files = convert.apply(e);
            if (!files.keySet().equals(new HashSet<>(e.sources()))) throw new IllegalStateException("Incomplete emitted source set for " + e.id());
            for (String relative : e.sources()) {
                Path path = safeRelative(root.resolve(e.id()).resolve("sources"), relative);
                Files.createDirectories(path.getParent());
                Files.writeString(path, files.get(relative));
                manifest.setProperty("entry." + i + ".sha256." + relative, sha256(path));
            }
        }
        List<String> lines = manifest.stringPropertyNames().stream().sorted().map(k -> escape(k) + "=" + escape(manifest.getProperty(k))).toList();
        Files.write(root.resolve("manifest.properties"), lines, StandardCharsets.UTF_8);
    }
    private static String escape(String text) { return text.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("=", "\\=").replace(":", "\\:"); }
    public static List<Entry> validate(Path root, String migration, String target, List<Entry> entries, ValidationEnvironment environments) throws IOException {
        Properties actual;
        try (var reader = Files.newBufferedReader(root.resolve("manifest.properties"))) { actual = read(reader); }
        Properties expected = expected(migration, target, entries, environments);
        Set<Path> expectedFiles = new HashSet<>();
        expectedFiles.add(root.resolve("manifest.properties"));
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            for (String relative : e.sources()) {
                Path file = safeRelative(root.resolve(e.id()).resolve("sources"), relative);
                if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) throw new IllegalStateException("Missing or unsafe emitted fixture: " + file);
                expectedFiles.add(file);
                expected.setProperty("entry." + i + ".sha256." + relative, sha256(file));
            }
        }
        if (!expected.equals(actual)) throw new IllegalStateException("Incomplete, conflicting, corrupt, or stale fixture manifest for " + migration);
        try (Stream<Path> tree = Files.walk(root)) {
            Set<Path> files = new HashSet<>(tree.filter(p -> !Files.isDirectory(p) || Files.isSymbolicLink(p)).toList());
            if (!files.equals(expectedFiles)) throw new IllegalStateException("Unexpected fixture files in " + root);
        }
        return entries;
    }
    public static String sha256(Path path) throws IOException {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
