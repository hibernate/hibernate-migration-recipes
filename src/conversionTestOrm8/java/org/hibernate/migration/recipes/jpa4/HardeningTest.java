/* SPDX-License-Identifier: Apache-2.0 */
package org.hibernate.migration.recipes.jpa4;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/// Exercises real source APIs, actual emitted code, fresh target parsing, and skip reports.
/// @author Steve Ebersole
class HardeningTest {
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

    @Test void targetMapKeyConflictsAndNoOps() {
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
        for (var row : result.skipped()) assertEquals("MAP_KEY_CONFLICT", row.getReasonCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"orm74", "jpa30", "jpa31", "jpa32"})
    void namedShapesAndComments(String api) {
        Map<String, String> sources = new LinkedHashMap<>();
        int index = 0;
        for (String kind : List.of("NamedQuery", "NamedNativeQuery")) {
            for (String shape : List.of("%s", "{%s}", "value = %s", "value = { /*array*/ %s /*close*/ }")) {
                String entry = "@jakarta.persistence." + kind + "(name = Names.NAME, /*query*/ query = \" uPdAtE Thing set n = :n\", hints = @jakarta.persistence.QueryHint(name = \"org.hibernate.timeout\", value = \"5\"))";
                String container = kind.equals("NamedQuery") ? "NamedQueries" : "NamedNativeQueries";
                String name = "Example" + index++;
                sources.put(name + ".java", "/*before*/ @jakarta.persistence." + container + "(" + shape.formatted(entry) + ")\n/*later*/ class " + name + " {}\n");
            }
        }
        sources.put("Names.java", "class Names { static final String NAME = \"mutation\"; }\n");
        var result = ApiValidation.run(new MigrateNamedQueryToStatement(), sources, api);
        assertTrue(result.skipped().isEmpty());
        for (var entry : sources.entrySet()) {
            if (entry.getKey().equals("Names.java")) continue;
            String after = result.files().get(entry.getKey());
            assertTrue(after.contains("statement = \" uPdAtE Thing set n = :n\""), after);
            assertFalse(after.contains("NamedQueries"));
            assertFalse(after.contains("NamedNativeQueries"));
            for (String comment : List.of("before", "query", "later", "array", "close")) assertEquals(occurrences(entry.getValue(), "/*" + comment + "*/"), occurrences(after, "/*" + comment + "*/"), after);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"orm74", "jpa30", "jpa31", "jpa32"})
    void mixedContainersAndOriginalReportLocations(String api) {
        String source = """
                import jakarta.persistence.*;
                @NamedQueries(value = { /*first*/
                    @NamedQuery(name = "update", query = "update Thing set n = 1"), /*between*/
                    @NamedQuery(name = "select", query = "select t from Thing t"),
                    @NamedQuery(name = "constant", query = Example.QUERY) /*end*/
                })
                @NamedNativeQueries({@NamedNativeQuery(name = "native", query = "with c as (select 1) delete from thing")})
                /*later*/ class Example {
                    static final String QUERY = "delete from Thing";
                    // unrelated
                    String value = "hello";
                }
                """;
        var result = ApiValidation.run(new MigrateNamedQueryToStatement(), source, api);
        assertTrue(result.text().contains("@NamedStatement(name = \"update\", statement = \"update Thing set n = 1\")"));
        assertTrue(result.text().contains("@NamedQuery(name = \"select\", query = \"select t from Thing t\")"));
        assertEquals(2, result.skipped().size());
        assertEquals(List.of("NON_LITERAL_QUERY", "UNSUPPORTED_QUERY_FORM"), result.skipped().stream().map(r -> r.getReasonCode()).toList());
        assertEquals(5, result.skipped().getFirst().getLine());
        assertEquals(5, result.skipped().getFirst().getColumn());
        assertEquals("Example.java", result.skipped().getFirst().getSourcePath());
        assertEquals(MigrateNamedQueryToStatement.class.getName(), result.skipped().getFirst().getRecipe());
        assertTrue(result.skipped().getFirst().getSubject().contains("constant"));
        for (String comment : List.of("first", "between", "end", "later")) assertEquals(1, occurrences(result.text(), "/*" + comment + "*/"), result.text());
        assertTrue(result.text().contains("// unrelated\n    String value = \"hello\";"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"orm74", "jpa30", "jpa31", "jpa32"})
    void queryGuards(String api) {
        Map<String, String> sources = new LinkedHashMap<>();
        List<String> expected = new ArrayList<>();
        int n = 0;
        for (String query : List.of("updated x", "update_suffix x", "update2 x", "update$ x", "updateλ x", "update𐐀 x", "with c as (select 1) delete from t", "/*lead*/ delete from t", "--lead\ndelete from t", "")) {
            sources.put("Q" + n + ".java", queryClass(n++, "NamedQuery", quote(query), "")); expected.add("UNSUPPORTED_QUERY_FORM");
        }
        for (String query : List.of("delete from t returning id", "update t set x=1 OUTPUT inserted.x")) {
            sources.put("Q" + n + ".java", queryClass(n++, "NamedNativeQuery", quote(query), "")); expected.add("NATIVE_RESULT_CLAUSE");
        }
        for (String query : List.of("delete from t; delete from t", "delete from t;;", "update t set x='unterminated", "delete from t /*oops", "delete from t /*nested /*no*/ */", "update t set x=$$output$$", "update t set x=q'[text]'", "update t set x='a\\b'")) {
            sources.put("Q" + n + ".java", queryClass(n++, "NamedNativeQuery", quote(query), "")); expected.add("UNSUPPORTED_NATIVE_SYNTAX");
        }
        sources.put("Q" + n + ".java", queryClass(n++, "NamedQuery", "\"delete \" + \"from t\"", "")); expected.add("NON_LITERAL_QUERY");
        sources.put("Q" + n + ".java", queryClass(n++, "NamedQuery", "(\"delete from t\")", "")); expected.add("NON_LITERAL_QUERY");
        sources.put("Q" + n + ".java", queryClass(n++, "NamedQuery", quote("delete from t"), ", lockMode=jakarta.persistence.LockModeType.NONE")); expected.add("INCOMPATIBLE_ANNOTATION_ATTRIBUTES");
        sources.put("Q" + n + ".java", queryClass(n++, "NamedNativeQuery", quote("delete from t returning id"), ", resultClass=void.class, resultSetMapping=\"\"")); expected.add("INCOMPATIBLE_ANNOTATION_ATTRIBUTES");
        var result = ApiValidation.run(new MigrateNamedQueryToStatement(), sources, api);
        assertEquals(sources, result.files());
        assertEquals(expected.stream().sorted().toList(), result.skipped().stream().map(r -> r.getReasonCode()).sorted().toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"orm74", "jpa30", "jpa31", "jpa32"})
    void nativeLexicalPositiveAndTextBlocks(String api) {
        Map<String, String> sources = new LinkedHashMap<>();
        int n = 0;
        for (String query : List.of("update t set x='it''s RETURNING'", "update \"OUT\"\"PUT\" set x=1", "delete from `RETURN``ING`", "delete from [OUT]]PUT]", "delete from t -- returning\n", "delete from t /* output */; -- end", "insert into t values (1)")) sources.put("Q" + n + ".java", queryClass(n++, "NamedNativeQuery", quote(query), ""));
        sources.put("Q" + n + ".java", queryClass(n, "NamedQuery", "\"\"\"\n        DELETE from Thing\n        \"\"\"", ""));
        var result = ApiValidation.run(new MigrateNamedQueryToStatement(), sources, api);
        assertTrue(result.skipped().isEmpty());
        assertEquals(sources.size(), occurrences(result.text(), "statement="));
    }

    @Test void targetContainersAndNameCollisions() {
        String source = """
                import jakarta.persistence.*;
                @NamedStatements({@NamedStatement(name="existing", statement="delete from Thing")})
                @NamedQuery(name="deferred", query="delete from Thing")
                class Example {}
                @NamedQuery(name="new", query="delete from Thing")
                class Other { static class NamedStatement {} }
                """;
        var result = ApiValidation.run(new MigrateNamedQueryToStatement(), source, ApiValidation.environments().primary().target());
        assertEquals(1, result.skipped().size());
        assertEquals("TARGET_CONTAINER_PRESENT", result.skipped().getFirst().getReasonCode());
        assertTrue(result.text().contains("@jakarta.persistence.NamedStatement(name=\"new\", statement=\"delete from Thing\")"));
        assertTrue(result.text().contains("@NamedQuery(name=\"deferred\", query=\"delete from Thing\")"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"orm74", "jpa30", "jpa31", "jpa32"})
    void delegateDispatch(String api) {
        String source = """
                import jakarta.persistence.EntityManager;
                abstract class Example implements EntityManager {
                    Object direct() { return getDelegate(/*inside*/); }
                    Object explicit() { return this.getDelegate(); }
                    EntityManager obtain() { return this; }
                    Object effect() { return obtain().getDelegate(); }
                    Object shadow() { class Object {} return getDelegate(); }
                }
                class Unrelated { Object getDelegate() { return null; } Object call() { return getDelegate(); } }
                """;
        var result = ApiValidation.run(new MigrateEntityManagerGetDelegate(), source, api);
        assertTrue(result.skipped().isEmpty());
        assertEquals(4, occurrences(result.text(), "java.lang.Object.class"));
        assertTrue(result.text().contains("obtain().unwrap(java.lang.Object.class)"));
        assertTrue(result.text().contains("unwrap(/*inside*/java.lang.Object.class)"));
        assertTrue(result.text().contains("Object call() { return getDelegate(); }"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"orm74", "jpa30", "jpa31", "jpa32"})
    void delegateSkips(String api) {
        String source = """
                import jakarta.persistence.EntityManager;
                import java.util.function.Supplier;
                abstract class Example implements EntityManager {
                    public Object getDelegate() { return this; }
                    Object own() { return getDelegate(); }
                    Supplier<Object> ref() { return this::getDelegate; }
                }
                class Other {
                    Object conflict(EntityManager em, Object java) { return em.getDelegate(); }
                    Supplier<Object> ref(EntityManager em) { return em::getDelegate; }
                }
                """;
        var result = ApiValidation.run(new MigrateEntityManagerGetDelegate(), source, api);
        assertEquals(source, result.text());
        assertEquals(List.of("NAME_RESOLUTION_CONFLICT", "UNSUPPORTED_METHOD_OVERRIDE", "UNSUPPORTED_METHOD_REFERENCE", "UNSUPPORTED_METHOD_REFERENCE"), result.skipped().stream().map(r -> r.getReasonCode()).sorted().toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"org.hibernate.migration.recipes.jpa4", "org.hibernate.migration.recipes.orm8"})
    void compositesKeepTemporalAndUnrelatedCode(String name) {
        String temporal = """
                    @Temporal(DATE) java.util.Date date = new java.util.Date();
                    @Temporal(TIME) java.util.Calendar time = java.util.Calendar.getInstance();
                    @Temporal(TIMESTAMP) java.util.Date timestamp;
                    java.util.Date unrelated = new java.util.Date(0);
                    java.util.Date getDate() { return date; }
                    void setDate(java.util.Date date) { this.date = date; }
                """;
        String source = "import jakarta.persistence.*;\nimport static jakarta.persistence.TemporalType.*;\n@NamedQuery(name=\"delete\", query=\"delete from Thing\")\nclass Example {\n" + temporal + "    @MapKey(name=\"id\") Object map;\n    Object delegate(EntityManager em) { return em.getDelegate(); }\n}\n";
        String unrelated = "package unrelated; @interface MapKey { String name(); }\nclass Other { @MapKey(name=\"id\") Object value; }\n";
        for (String api : List.of("orm74", "jpa30", "jpa31", "jpa32")) {
            var result = ApiValidation.run(ApiValidation.composite(name), Map.of("Example.java", source, "Other.java", unrelated), api);
            assertTrue(result.files().get("Example.java").contains(temporal));
            assertEquals(unrelated, result.files().get("Other.java"));
            assertTrue(result.text().contains("unwrap(java.lang.Object.class)"));
            assertTrue(result.text().contains("@MapKey(\"id\")"));
            assertTrue(result.skipped().isEmpty());
        }
    }

    @Test void targetRepeatabilityAndEmptyContainers() {
        String source = """
                import jakarta.persistence.*;
                @NamedStatement(name="old", statement="delete from Thing")
                @NamedQuery(name="new", query="delete from Thing")
                class Example {
                    @NamedQueries({}) class Empty {}
                    @NamedQueries(value={@NamedQuery(name="nested", query="update Thing set n=1")}) class Nested {}
                }
                """;
        var result = ApiValidation.run(new MigrateNamedQueryToStatement(), source, ApiValidation.environments().primary().target());
        assertTrue(result.skipped().isEmpty());
        assertEquals(3, occurrences(result.text(), "@NamedStatement("));
        assertTrue(result.text().contains("@NamedQueries({}) class Empty {}"));
    }

    @Test void delegatePackageTypeShadowAndSuperOverride() {
        String source = """
                import jakarta.persistence.EntityManager;
                class java {}
                abstract class Base implements EntityManager {
                    public Object getDelegate() { return this; }
                }
                abstract class Example extends Base {
                    Object inherited() { return super.getDelegate(); }
                    Object call(EntityManager em) { return em.getDelegate(); }
                }
                """;
        var result = ApiValidation.run(new MigrateEntityManagerGetDelegate(), source, "jpa32");
        assertEquals(source, result.text());
        assertEquals(List.of("UNSUPPORTED_METHOD_OVERRIDE", "NAME_RESOLUTION_CONFLICT"), result.skipped().stream().map(r -> r.getReasonCode()).toList());
    }

    @Test void explicitJpa32ResultClassIsAConflict() {
        String source = "@jakarta.persistence.NamedQuery(name=\"x\", query=\"delete from Thing\", resultClass=void.class) class Example {}";
        var result = ApiValidation.run(new MigrateNamedQueryToStatement(), source, "jpa32");
        assertEquals(source, result.text());
        assertEquals("INCOMPATIBLE_ANNOTATION_ATTRIBUTES", result.skipped().getFirst().getReasonCode());
    }

    @Test void crlfConversionPreservesLineEndings() {
        String source = "@jakarta.persistence.NamedQueries({\r\n    @jakarta.persistence.NamedQuery(name=\"x\", query=\"delete from Thing\")\r\n})\r\nclass Example {}\r\n";
        var result = ApiValidation.run(new MigrateNamedQueryToStatement(), source, "jpa32");
        assertFalse(result.text().replace("\r\n", "").contains("\n"));
        assertTrue(result.skipped().isEmpty());
    }

    private static String queryClass(int n, String annotation, String query, String attributes) { return "@jakarta.persistence." + annotation + "(name=\"q" + n + "\", query=" + query + attributes + ") class Q" + n + " {}\n"; }
    private static String quote(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""; }
    static int occurrences(String s, String part) { return (s.length() - s.replace(part, "").length()) / part.length(); }
}
