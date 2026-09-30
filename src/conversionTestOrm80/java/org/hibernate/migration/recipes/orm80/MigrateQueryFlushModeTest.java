package org.hibernate.migration.recipes.orm80;

import java.util.Map;

import org.hibernate.migration.testing.ApiValidation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Validates enum relocation against the ORM 7.4 and ORM 8.0 APIs.
/// @author Steve Ebersole
class MigrateQueryFlushModeTest {
    private static final String RECIPE = "org.hibernate.migration.recipes.orm80.MigrateQueryFlushMode";

    @ParameterizedTest
    @ValueSource(strings = {RECIPE, "org.hibernate.migration.recipes.orm80"})
    void migratesDeclarationsQueryCallsAndAnnotationValues(String recipe) {
        String source = """
                import org.hibernate.query.Query;
                import org.hibernate.query.QueryFlushMode;
                import org.hibernate.annotations.NamedQuery;
                import org.hibernate.annotations.NamedNativeQuery;
                import static org.hibernate.query.QueryFlushMode.FLUSH;
                import static org.hibernate.query.QueryFlushMode.*;

                @NamedQuery(name = "all", query = "from Thing", flush = FLUSH)
                @NamedNativeQuery(name = "nativeAll", query = "select * from Thing", flush = NO_FLUSH)
                class Example {
                    QueryFlushMode mode = QueryFlushMode.DEFAULT;
                    java.util.List<QueryFlushMode> modes = java.util.List.of(FLUSH, NO_FLUSH, mode);
                    QueryFlushMode apply(Query<?> query, QueryFlushMode mode) {
                        query.setQueryFlushMode(mode);
                        return query.getQueryFlushMode();
                    }
                    Query<?> chained(Query<?> query) {
                        return query.setQueryFlushMode(FLUSH).setMaxResults(10);
                    }
                    Class<QueryFlushMode> type = QueryFlushMode.class;
                    QueryFlushMode parsed = QueryFlushMode.valueOf("DEFAULT");
                    QueryFlushMode[] values = QueryFlushMode.values();
                    int code(QueryFlushMode mode) {
                        return switch (mode) {
                            case FLUSH -> 1;
                            case NO_FLUSH -> 2;
                            case DEFAULT -> 3;
                        };
                    }
                }
                """;
        var result = ApiValidation.run(ApiValidation.composite(recipe), source, "orm74");
        assertFalse(result.text().contains("org.hibernate.query.QueryFlushMode"));
        assertTrue(result.text().contains("import jakarta.persistence.QueryFlushMode;"));
        assertTrue(result.text().contains("import static jakarta.persistence.QueryFlushMode.*;"));
        assertEquals(source.substring(source.indexOf("@NamedQuery")),
                result.text().substring(result.text().indexOf("@NamedQuery")));
        assertTrue(result.skipped().isEmpty());
    }

    @Test
    void qualifiedReferencesPreserveUnrelatedTypeAndStrings() {
        String source = """
                class Example {
                    enum QueryFlushMode { FLUSH }
                    QueryFlushMode unrelated = QueryFlushMode.FLUSH;
                    org.hibernate.query.QueryFlushMode mode = org.hibernate.query.QueryFlushMode.NO_FLUSH;
                    String name = "org.hibernate.query.QueryFlushMode";
                }
                """;
        var result = ApiValidation.run(ApiValidation.composite(RECIPE), source, "orm74");
        assertTrue(result.text().contains("jakarta.persistence.QueryFlushMode mode = jakarta.persistence.QueryFlushMode.NO_FLUSH;"));
        assertTrue(result.text().contains("QueryFlushMode unrelated = QueryFlushMode.FLUSH;"));
        assertTrue(result.text().contains("String name = \"org.hibernate.query.QueryFlushMode\";"));
        assertFalse(result.text().contains("import jakarta.persistence.QueryFlushMode;"));
    }

    @Test
    void migratesWildcardImportAcrossFiles() {
        Map<String, String> sources = Map.of(
                "Modes.java", """
                        import org.hibernate.query.*;
                        class Modes {
                            static QueryFlushMode mode = QueryFlushMode.DEFAULT;
                        }
                        """,
                "Example.java", """
                        class Example {
                            org.hibernate.query.QueryFlushMode mode() { return Modes.mode; }
                        }
                        """);
        var result = ApiValidation.run(ApiValidation.composite(RECIPE), sources, "orm74");
        assertTrue(result.files().get("Modes.java").contains("import jakarta.persistence.QueryFlushMode;"));
        assertFalse(result.files().get("Example.java").contains("org.hibernate.query.QueryFlushMode"));
    }

    @Test
    void unrelatedEnumIsUnchanged() {
        String source = """
                class Example {
                    enum QueryFlushMode { FLUSH, NO_FLUSH, DEFAULT }
                    QueryFlushMode mode = QueryFlushMode.DEFAULT;
                }
                """;
        assertEquals(source, ApiValidation.run(ApiValidation.composite(RECIPE), source, "orm74").text());
    }
}
