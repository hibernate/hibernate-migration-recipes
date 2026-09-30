package org.hibernate.migration.recipes.orm80;

import org.hibernate.migration.testing.ApiValidation;
import org.hibernate.migration.testing.MigrationSources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Validates enum relocation against the ORM 7.4 and ORM 8.0 APIs.
/// @author Steve Ebersole
class MigrateQueryFlushModeTest {
    private static final String RECIPE = "org.hibernate.migration.recipes.orm80.MigrateQueryFlushMode";

    @Test
    void compiledInputClassesAreNotOnTheTestRuntimeClasspath() {
        assertNull(getClass().getClassLoader().getResource("fixture/queryflush/calls/Example.class"));
        assertNull(getClass().getClassLoader().getResource("fixture/Migrated.class"));
    }

    @ParameterizedTest
    @ValueSource(strings = {RECIPE, "org.hibernate.migration.recipes.orm80"})
    void migratesDeclarationsQueryCallsAndAnnotationValues(String recipe) {
        String path = "fixture/queryflush/calls/Example.java";
        var sources = MigrationSources.configured().files(path);
        String source = sources.get(path);
        var result = ApiValidation.run(ApiValidation.composite(recipe), sources, "orm74");
        assertFalse(result.text().contains("org.hibernate.query.QueryFlushMode"));
        assertTrue(result.text().contains("import jakarta.persistence.QueryFlushMode;"));
        assertTrue(result.text().contains("import static jakarta.persistence.QueryFlushMode.*;"));
        assertEquals(source.substring(source.indexOf("@NamedQuery")),
                result.text().substring(result.text().indexOf("@NamedQuery")));
        assertTrue(result.skipped().isEmpty());
    }

    @Test
    void qualifiedReferencesPreserveUnrelatedTypeAndStrings() {
        String path = "fixture/queryflush/qualified/Example.java";
        var sources = MigrationSources.configured().files(path);
        var result = ApiValidation.run(ApiValidation.composite(RECIPE), sources, "orm74");
        assertTrue(result.text().contains("jakarta.persistence.QueryFlushMode mode = jakarta.persistence.QueryFlushMode.NO_FLUSH;"));
        assertTrue(result.text().contains("QueryFlushMode unrelated = QueryFlushMode.FLUSH;"));
        assertTrue(result.text().contains("String name = \"org.hibernate.query.QueryFlushMode\";"));
        assertFalse(result.text().contains("import jakarta.persistence.QueryFlushMode;"));
    }

    @Test
    void migratesWildcardImportAcrossFiles() {
        var sources = MigrationSources.configured().scenario("fixture/queryflush/multifile");
        var result = ApiValidation.run(ApiValidation.composite(RECIPE), sources, "orm74");
        assertTrue(result.files().get("fixture/queryflush/multifile/Modes.java").contains("import jakarta.persistence.QueryFlushMode;"));
        assertFalse(result.files().get("fixture/queryflush/multifile/Example.java").contains("org.hibernate.query.QueryFlushMode"));
    }

    @Test
    void unrelatedEnumIsUnchanged() {
        String path = "fixture/queryflush/unrelated/Example.java";
        var sources = MigrationSources.configured().files(path);
        String source = sources.get(path);
        assertEquals(source, ApiValidation.run(ApiValidation.composite(RECIPE), sources, "orm74").text());
    }
}
