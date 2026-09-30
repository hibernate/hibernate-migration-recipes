package org.hibernate.migration.recipes.jpa4;

import org.hibernate.migration.testing.ApiValidation;
import org.hibernate.migration.testing.MigrationSources;
import org.hibernate.migration.recipes.table.SkippedMigrations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/// Validates delegate dispatch and name-resolution boundaries against source and target APIs.
/// @author Steve Ebersole
class EntityManagerDelegateConversionTest {
    @ParameterizedTest
    @ValueSource(strings = {"orm74", "jpa30", "jpa31", "jpa32"})
    void delegateDispatch(String api) {
        var sourceFiles = MigrationSources.configured().files("fixture/jpa4/entitymanagerdelegateconversion/delegatedispatch/Example.java");
        String source = sourceFiles.get("fixture/jpa4/entitymanagerdelegateconversion/delegatedispatch/Example.java");
        var result = ApiValidation.run(new MigrateEntityManagerGetDelegate(), sourceFiles, api);
        assertTrue(result.skipped().isEmpty());
        assertEquals(4, occurrences(result.text(), "java.lang.Object.class"));
        assertTrue(result.text().contains("obtain().unwrap(java.lang.Object.class)"));
        assertTrue(result.text().matches("(?s).*unwrap\\(/\\*inside\\*/\\s*java\\.lang\\.Object\\.class\\).*"));
        assertTrue(result.text().matches("(?s).*Object call\\(\\)\\s*\\{\\s*return getDelegate\\(\\);\\s*}.*"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"orm74", "jpa30", "jpa31", "jpa32"})
    void delegateSkips(String api) {
        String path = "fixture/jpa4/entitymanagerdelegateconversion/delegateskips/Example.java";
        var sourceFiles = MigrationSources.configured().files(path);
        String source = sourceFiles.get(path);
        var result = ApiValidation.run(new MigrateEntityManagerGetDelegate(), sourceFiles, api);
        assertEquals(source, result.text());
        assertReasonsByLine(result.skipped(), Map.of(
                13, "UNSUPPORTED_METHOD_OVERRIDE",
                17, "UNSUPPORTED_METHOD_REFERENCE",
                23, "NAME_RESOLUTION_CONFLICT",
                27, "UNSUPPORTED_METHOD_REFERENCE"), api, path);
    }

    @Test
    void delegatePackageTypeShadowAndSuperOverride() {
        String path = "fixture/jpa4/entitymanagerdelegateconversion/delegatepackagetypeshadowandsuperoverride/Example.java";
        var sourceFiles = MigrationSources.configured().files(path);
        String source = sourceFiles.get(path);
        var result = ApiValidation.run(new MigrateEntityManagerGetDelegate(), sourceFiles, "jpa32");
        assertEquals(source, result.text());
        assertReasonsByLine(result.skipped(), Map.of(7, "UNSUPPORTED_METHOD_OVERRIDE", 11, "NAME_RESOLUTION_CONFLICT"), "jpa32", path);
    }

    @ParameterizedTest
    @ValueSource(strings = {"orm74", "jpa30", "jpa31", "jpa32"})
    void delegateTypeParameterConflicts(String api) {
        String path = "fixture/jpa4/entitymanagerdelegateconversion/delegatetypeparameterconflicts/Example.java";
        var sourceFiles = MigrationSources.configured().files(path);
        String source = sourceFiles.get(path);
        var result = ApiValidation.run(new MigrateEntityManagerGetDelegate(), sourceFiles, api);
        assertEquals(source, result.text());
        assertReasonsByLine(result.skipped(), Map.of(7, "NAME_RESOLUTION_CONFLICT", 13, "NAME_RESOLUTION_CONFLICT"), api, path);
    }

    @ParameterizedTest
    @ValueSource(strings = {"orm74", "jpa30", "jpa31", "jpa32"})
    void delegateInheritedMemberTypeConflictsAcrossFiles(String api) {
        String base = MigrationSources.configured().read("fixture/jpa4/entitymanagerdelegateconversion/delegateinheritedmembertypeconflictsacrossfiles/Base.java");
        String path = "fixture/jpa4/entitymanagerdelegateconversion/delegateinheritedmembertypeconflictsacrossfiles/Example.java";
        var sourceFiles = MigrationSources.configured().files(path);
        String source = sourceFiles.get(path);
        String unrelated = MigrationSources.configured().read("fixture/jpa4/entitymanagerdelegateconversion/delegateinheritedmembertypeconflictsacrossfiles/Unrelated.java");
        var sources = new java.util.LinkedHashMap<String, String>();
        // The subtype precedes the member-type declaration to exercise scan-before-edit behavior.
        sources.put(path, source);
        sources.put("fixture/jpa4/entitymanagerdelegateconversion/delegateinheritedmembertypeconflictsacrossfiles/Unrelated.java", unrelated);
        sources.put("fixture/jpa4/entitymanagerdelegateconversion/delegateinheritedmembertypeconflictsacrossfiles/Base.java", base);
        var result = ApiValidation.run(new MigrateEntityManagerGetDelegate(), sources, api);
        assertEquals(source, result.files().get(path));
        assertEquals(base, result.files().get("fixture/jpa4/entitymanagerdelegateconversion/delegateinheritedmembertypeconflictsacrossfiles/Base.java"));
        assertTrue(result.files().get("fixture/jpa4/entitymanagerdelegateconversion/delegateinheritedmembertypeconflictsacrossfiles/Unrelated.java").contains("unwrap(java.lang.Object.class)"));
        assertReasonsByLine(result.skipped(), Map.of(10, "NAME_RESOLUTION_CONFLICT"), api, path);
    }

    private static void assertReasonsByLine(List<SkippedMigrations.Row> rows, Map<Integer, String> expected, String api, String path) {
        assertEquals(expected.size(), rows.size(), api + ": exact candidate count");
        var byLine = rows.stream().collect(java.util.stream.Collectors.groupingBy(SkippedMigrations.Row::getLine));
        assertEquals(expected.keySet(), byLine.keySet(), api + ": original candidate lines");
        expected.forEach((line, reason) -> {
            var candidates = byLine.get(line);
            String context = api + " / " + path + ":" + line;
            assertEquals(1, candidates.size(), context);
            assertEquals(path, candidates.getFirst().getSourcePath(), context);
            assertEquals(reason, candidates.getFirst().getReasonCode(), context);
        });
    }

    private static int occurrences(String s, String part) { return (s.length() - s.replace(part, "").length()) / part.length(); }
}
