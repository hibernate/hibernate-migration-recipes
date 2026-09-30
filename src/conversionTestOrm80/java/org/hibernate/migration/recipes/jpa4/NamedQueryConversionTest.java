package org.hibernate.migration.recipes.jpa4;

import org.hibernate.migration.testing.ApiValidation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/// Validates named-query shapes, lexical boundaries, comments, and candidate diagnostics.
/// @author Steve Ebersole
class NamedQueryConversionTest {
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
            assertTrue(after.contains("statement = \" uPdAtE Thing set n = :n\""), api + " / " + entry.getKey() + "\n" + after);
            assertFalse(after.contains("NamedQueries"), api + " / " + entry.getKey() + "\n" + after);
            assertFalse(after.contains("NamedNativeQueries"), api + " / " + entry.getKey() + "\n" + after);
            for (String comment : List.of("before", "query", "later", "array", "close")) assertEquals(occurrences(entry.getValue(), "/*" + comment + "*/"), occurrences(after, "/*" + comment + "*/"), api + " / " + entry.getKey() + "\n" + after);
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
        var byLine = result.skipped().stream().collect(java.util.stream.Collectors.toMap(row -> row.getLine(), row -> row));
        assertEquals(Set.of(5, 7), byLine.keySet(), api + ": original query locations");
        assertEquals("NON_LITERAL_QUERY", byLine.get(5).getReasonCode(), api + ": constant query");
        assertEquals("UNSUPPORTED_QUERY_FORM", byLine.get(7).getReasonCode(), api + ": native CTE");
        assertEquals(5, byLine.get(5).getLine());
        assertEquals(5, byLine.get(5).getColumn());
        assertEquals("Example.java", byLine.get(5).getSourcePath());
        assertEquals(MigrateNamedQueryToStatement.class.getName(), byLine.get(5).getRecipe());
        assertTrue(byLine.get(5).getSubject().contains("constant"));
        for (String comment : List.of("first", "between", "end", "later")) assertEquals(1, occurrences(result.text(), "/*" + comment + "*/"), result.text());
        assertTrue(result.text().contains("// unrelated\n    String value = \"hello\";"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"orm74", "jpa30", "jpa31", "jpa32"})
    void queryGuards(String api) {
        List<QueryGuardCase> cases = queryGuardCases();
        Map<String, String> sources = new LinkedHashMap<>();
        for (QueryGuardCase guard : cases) sources.put(guard.name() + ".java", guard.source());
        var result = ApiValidation.run(new MigrateNamedQueryToStatement(), sources, api);
        assertEquals(sources, result.files(), api + ": unsupported queries must remain unchanged");
        assertEquals(cases.size(), result.skipped().size(), api + ": exact number of candidate reports");
        var byPath = result.skipped().stream().collect(java.util.stream.Collectors.groupingBy(row -> row.getSourcePath()));
        assertEquals(sources.keySet(), byPath.keySet(), api + ": reports must identify the expected sources");
        for (QueryGuardCase guard : cases) {
            String context = api + " / " + guard.name();
            var rows = byPath.get(guard.name() + ".java");
            assertEquals(1, rows.size(), context + ": one diagnostic per candidate");
            assertEquals(guard.reason(), rows.getFirst().getReasonCode(), context);
        }
    }

    private record QueryGuardCase(String name, String annotation, String queryExpression, String attributes, String reason) {
        String source() { return queryClass(name, annotation, queryExpression, attributes); }
    }

    private static List<QueryGuardCase> queryGuardCases() {
        return List.of(
                hqlGuard("KeywordSuffix", "updated x"),
                hqlGuard("UnderscoreSuffix", "update_suffix x"),
                hqlGuard("DigitSuffix", "update2 x"),
                hqlGuard("DollarSuffix", "update$ x"),
                hqlGuard("UnicodeSuffix", "updateλ x"),
                hqlGuard("SupplementarySuffix", "update𐐀 x"),
                hqlGuard("CommonTableExpression", "with c as (select 1) delete from t"),
                hqlGuard("LeadingBlockComment", "/*lead*/ delete from t"),
                hqlGuard("LeadingLineComment", "--lead\ndelete from t"),
                hqlGuard("EmptyQuery", ""),
                nativeGuard("ReturningClause", "delete from t returning id", "NATIVE_RESULT_CLAUSE"),
                nativeGuard("OutputClause", "update t set x=1 OUTPUT inserted.x", "NATIVE_RESULT_CLAUSE"),
                nativeGuard("MultipleStatements", "delete from t; delete from t", "UNSUPPORTED_NATIVE_SYNTAX"),
                nativeGuard("RepeatedSemicolon", "delete from t;;", "UNSUPPORTED_NATIVE_SYNTAX"),
                nativeGuard("UnterminatedQuote", "update t set x='unterminated", "UNSUPPORTED_NATIVE_SYNTAX"),
                nativeGuard("UnterminatedComment", "delete from t /*oops", "UNSUPPORTED_NATIVE_SYNTAX"),
                nativeGuard("NestedComment", "delete from t /*nested /*no*/ */", "UNSUPPORTED_NATIVE_SYNTAX"),
                nativeGuard("DollarQuote", "update t set x=$$output$$", "UNSUPPORTED_NATIVE_SYNTAX"),
                nativeGuard("OracleQuote", "update t set x=q'[text]'", "UNSUPPORTED_NATIVE_SYNTAX"),
                nativeGuard("BackslashQuote", "update t set x='a\\b'", "UNSUPPORTED_NATIVE_SYNTAX"),
                new QueryGuardCase("Concatenation", "NamedQuery", "\"delete \" + \"from t\"", "", "NON_LITERAL_QUERY"),
                new QueryGuardCase("ParenthesizedLiteral", "NamedQuery", "(\"delete from t\")", "", "NON_LITERAL_QUERY"),
                new QueryGuardCase("ExplicitLockMode", "NamedQuery", quote("delete from t"),
                        ", lockMode=jakarta.persistence.LockModeType.NONE", "INCOMPATIBLE_ANNOTATION_ATTRIBUTES"),
                new QueryGuardCase("ResultAttributesBeforeReturningClause", "NamedNativeQuery", quote("delete from t returning id"),
                        ", resultClass=void.class, resultSetMapping=\"\"", "INCOMPATIBLE_ANNOTATION_ATTRIBUTES")
        );
    }

    private static QueryGuardCase hqlGuard(String name, String query) {
        return new QueryGuardCase(name, "NamedQuery", quote(query), "", "UNSUPPORTED_QUERY_FORM");
    }

    private static QueryGuardCase nativeGuard(String name, String query, String reason) {
        return new QueryGuardCase(name, "NamedNativeQuery", quote(query), "", reason);
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
        for (var entry : result.files().entrySet()) {
            assertEquals(1, occurrences(entry.getValue(), "statement="), api + " / " + entry.getKey() + "\n" + entry.getValue());
        }
    }

    @Test
    void targetContainersAndNameCollisions() {
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

    @Test
    void targetRepeatabilityAndEmptyContainers() {
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

    @ParameterizedTest
    @ValueSource(strings = {"orm74", "jpa30", "jpa31", "jpa32"})
    void namedQueryTypeParameterConflicts(String api) {
        String source = """
                import jakarta.persistence.NamedQuery;
                import jakarta.persistence.NamedNativeQuery;
                @NamedQuery(name="update", query="update Thing set id=1")
                class Example<NamedStatement> {}
                @NamedNativeQuery(name="nativeUpdate", query="update thing set id=1")
                class NativeExample<NamedNativeStatement> {}
                """;
        var result = ApiValidation.run(new MigrateNamedQueryToStatement(), source, api);
        assertTrue(result.text().contains("@jakarta.persistence.NamedStatement("));
        assertTrue(result.text().contains("@jakarta.persistence.NamedNativeStatement("));
        assertTrue(result.text().contains("class Example<NamedStatement>"));
        assertTrue(result.text().contains("class NativeExample<NamedNativeStatement>"));
        assertTrue(result.skipped().isEmpty());
    }

    @Test
    void explicitJpa32ResultClassIsAConflict() {
        String source = "@jakarta.persistence.NamedQuery(name=\"x\", query=\"delete from Thing\", resultClass=void.class) class Example {}";
        var result = ApiValidation.run(new MigrateNamedQueryToStatement(), source, "jpa32");
        assertEquals(source, result.text());
        assertEquals("INCOMPATIBLE_ANNOTATION_ATTRIBUTES", result.skipped().getFirst().getReasonCode());
    }

    @Test
    void crlfConversionPreservesLineEndings() {
        String source = "@jakarta.persistence.NamedQueries({\r\n    @jakarta.persistence.NamedQuery(name=\"x\", query=\"delete from Thing\")\r\n})\r\nclass Example {}\r\n";
        var result = ApiValidation.run(new MigrateNamedQueryToStatement(), source, "jpa32");
        assertFalse(result.text().replace("\r\n", "").contains("\n"));
        assertTrue(result.skipped().isEmpty());
    }

    @Test
    void identicalCandidatesAtDifferentLocationsAndCrLf() {
        String source = "import jakarta.persistence.*;\r\n@NamedQuery(name=\"same\", query=\"with unsupported\") class A {}\r\n@NamedQuery(name=\"same\", query=\"with unsupported\") class B {}\r\n";
        var result = ApiValidation.run(new MigrateNamedQueryToStatement(), source, "jpa32");
        assertEquals(source, result.text());
        assertEquals(List.of(2, 3), result.skipped().stream().map(r -> r.getLine()).sorted().toList());
        assertTrue(result.skipped().stream().allMatch(r -> r.getColumn() == 1));
    }

    private static String queryClass(int n, String annotation, String query, String attributes) {
        return "@jakarta.persistence." + annotation + "(name=\"q" + n + "\", query=" + query + attributes
                + ") class Q" + n + " {}\n";
    }

    private static String queryClass(String name, String annotation, String query, String attributes) {
        return "@jakarta.persistence." + annotation + "(name=\"" + name + "\", query=" + query + attributes
                + ") class " + name + " {}\n";
    }
    private static String quote(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""; }
    static int occurrences(String s, String part) { return (s.length() - s.replace(part, "").length()) / part.length(); }
}
