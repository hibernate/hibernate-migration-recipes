/* SPDX-License-Identifier: Apache-2.0 */
package org.hibernate.migration.testing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/// Exercises incomplete generation, independent variants, and handoff integrity failures.
/// @author Steve Ebersole
class FixtureBundleUnitTest {
    @TempDir Path temporary;
    private final List<FixtureBundle.Entry> entries = List.of(
            new FixtureBundle.Entry("same", "sourceA", "recipe", "scenario", List.of("Example.java")),
            new FixtureBundle.Entry("same", "sourceB", "recipe", "scenario", List.of("Example.java")));
    private ValidationEnvironment environments() {
        Properties p = new Properties();
        p.setProperty("sourceA.modules", "source:a:1");
        p.setProperty("sourceB.modules", "source:b:1");
        p.setProperty("target.modules", "target:t:1");
        return new ValidationEnvironment(p);
    }
    private Path publish() throws Exception {
        Path output = temporary.resolve("output");
        FixtureBundle.publish(output, "migration", "target", entries, environments(), e -> Map.of("Example.java", "class Example { String variant = \"" + e.variant() + "\"; }"));
        return output;
    }
    @Test void distinctVariantsAndDeterministicManifest() throws Exception {
        Path output = publish();
        assertEquals(entries, FixtureBundle.validate(output, "migration", "target", entries, environments()));
        assertNotEquals(Files.readString(output.resolve("sourceA/same/sources/Example.java")), Files.readString(output.resolve("sourceB/same/sources/Example.java")));
        String manifest = Files.readString(output.resolve("manifest.properties"));
        publish();
        assertEquals(manifest, Files.readString(output.resolve("manifest.properties")));
    }
    @Test void failureRemovesPreviousSuccessManifest() throws Exception {
        Path output = publish();
        assertThrows(IllegalStateException.class, () -> FixtureBundle.publish(output, "migration", "target", entries, environments(), e -> { throw new IllegalStateException("conversion failed"); }));
        assertFalse(Files.exists(output.resolve("manifest.properties")));
        assertThrows(Exception.class, () -> FixtureBundle.validate(output, "migration", "target", entries, environments()));
    }
    @Test void missingCorruptExtraAndStaleFilesAreRejected() throws Exception {
        for (String fault : List.of("missing", "corrupt", "extra", "stale", "incomplete", "duplicate")) {
            Path output = publish();
            Path file = output.resolve("sourceA/same/sources/Example.java");
            Path manifest = output.resolve("manifest.properties");
            switch (fault) {
                case "missing" -> Files.delete(file);
                case "corrupt" -> Files.writeString(file, "changed");
                case "extra" -> Files.writeString(output.resolve("unexpected.java"), "extra");
                case "stale" -> Files.writeString(manifest, Files.readString(manifest).replace("target\\:t\\:1", "target\\:t\\:2"));
                case "incomplete" -> Files.writeString(manifest, Files.readString(manifest).replace("entries=2", "entries=1"));
                case "duplicate" -> Files.writeString(manifest, "entries=2\n", StandardOpenOption.APPEND);
            }
            assertThrows(Exception.class, () -> FixtureBundle.validate(output, "migration", "target", entries, environments()), fault);
        }
    }
    @Test void unsafePathsAndUnknownProfilesAreRejected() {
        for (String relative : List.of("../escape.java", "/absolute.java", "a/../escape.java", "a\\escape.java")) assertThrows(IllegalArgumentException.class, () -> FixtureBundle.safeRelative(temporary, relative));
        Properties p = new Properties(); p.setProperty("profiles", "known");
        assertThrows(IllegalArgumentException.class, () -> new ValidationEnvironment(p).paths("unknown"));
    }
}
