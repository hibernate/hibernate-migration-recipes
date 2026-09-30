package org.hibernate.migration.recipes.jpa4;

import org.hibernate.migration.testing.ApiValidation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/// Validates map-key conversion and conflicting attributes against source and target APIs.
/// @author Steve Ebersole
class MapKeyConversionTest {
    @ParameterizedTest
    @ValueSource(strings = {"orm74", "jpa30", "jpa31", "jpa32"})
    void mapKeys(String api) {
        String source = """
                import jakarta.persistence.*;
                import java.util.Map;
                class Example {
                    static final String KEY = "id";
                    @MapKey(/*before*/ name /*equal*/ = /*value*/ KEY /*after*/)
                    Map<String, Object> values;
                    @jakarta.persistence.MapKey(name = "") Map<String, Object> empty;
                    @MapKey(name = "id") Map<String, Object> getValues() { return values; }
                    @MapKey Map<String, Object> plain;
                    String unrelated = "name";
                }
                """;
        var result = ApiValidation.run(new MigrateMapKeyNameToValue(), source, api);
        assertTrue(result.skipped().isEmpty());
        assertTrue(result.text().contains("@jakarta.persistence.MapKey(\"\")"));
        assertTrue(result.text().contains("@MapKey(\"id\") Map<String, Object> getValues()"));
        for (String comment : List.of("before", "equal", "value", "after")) assertEquals(1, occurrences(result.text(), "/*" + comment + "*/"), result.text());
        assertTrue(result.text().contains("String unrelated = \"name\";"));
    }

    @Test
    void targetMapKeyConflictsAndNoOps() {
        String source = """
                import jakarta.persistence.MapKey;
                class Example {
                    @MapKey(name = "x", value = "x") Object equal;
                    @MapKey(name = "x", value = "y") Object different;
                    @MapKey(name = "", value = "") Object empty;
                    @MapKey(value = "x") Object explicit;
                    @MapKey("x") Object shorthand;
                    @MapKey Object plain;
                }
                """;
        var result = ApiValidation.run(new MigrateMapKeyNameToValue(), source, ApiValidation.environments().primary().target());
        assertEquals(source, result.text());
        assertEquals(3, result.skipped().size());
        assertEquals(List.of(3, 4, 5), result.skipped().stream().map(row -> row.getLine()).sorted().toList(),
                "Only conflicting attributes should be reported");
        for (var row : result.skipped()) {
            assertEquals("Example.java", row.getSourcePath());
            assertEquals("MAP_KEY_CONFLICT", row.getReasonCode(), "Example.java:" + row.getLine());
        }
    }

    private static int occurrences(String s, String part) { return (s.length() - s.replace(part, "").length()) / part.length(); }
}
