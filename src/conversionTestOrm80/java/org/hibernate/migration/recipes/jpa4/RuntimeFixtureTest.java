package org.hibernate.migration.recipes.jpa4;

import org.junit.jupiter.api.Test;
import org.hibernate.migration.testing.ApiValidation;
import org.hibernate.migration.testing.MigrationSources;

import static org.junit.jupiter.api.Assertions.*;

/// Validates the reusable integration fixture without publishing handoff files.
/// @author Steve Ebersole
class RuntimeFixtureTest {
    @Test
    void validatesRuntimeFixture() throws Exception {
        String api = "orm74";
        var sources = MigrationSources.configured().files("fixture/Migrated.java");
        var result = ApiValidation.run(ApiValidation.composite("org.hibernate.migration.recipes.orm80"), sources, api);
        assertTrue(result.skipped().isEmpty());
        String output = result.files().get("fixture/Migrated.java");
        assertEquals(4, occurrences(output, "@NamedStatement("));
        assertEquals(3, occurrences(output, "@NamedNativeStatement("));
    }
    private static int occurrences(String s, String part) { return (s.length() - s.replace(part, "").length()) / part.length(); }
}
