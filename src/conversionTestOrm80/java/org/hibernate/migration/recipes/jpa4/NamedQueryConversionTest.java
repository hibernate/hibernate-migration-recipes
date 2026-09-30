package org.hibernate.migration.recipes.jpa4;

import org.hibernate.migration.testing.ApiValidation;
import org.hibernate.migration.testing.MigrationSources;
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
        var sources = MigrationSources.configured().scenario("fixture/jpa4/namedqueryconversion/namedshapesandcomments");
        var result = ApiValidation.run(new MigrateNamedQueryToStatement(), sources, api);
        assertTrue(result.skipped().isEmpty());
        for (var entry : sources.entrySet()) {
            if (entry.getKey().endsWith("/Names.java")) continue;
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
        String path = "fixture/jpa4/namedqueryconversion/mixedcontainersandoriginalreportlocations/Example.java";
        var sourceFiles = MigrationSources.configured().files(path);
        String source = sourceFiles.get(path);
        var result = ApiValidation.run(new MigrateNamedQueryToStatement(), sourceFiles, api);
        assertTrue(result.text().contains("@NamedStatement(name = \"update\", statement = \"update Thing set n = 1\")"));
        assertTrue(result.text().contains("@NamedQuery(name = \"select\", query = \"select t from Thing t\")"));
        assertEquals(2, result.skipped().size());
        var byLine = result.skipped().stream().collect(java.util.stream.Collectors.toMap(row -> row.getLine(), row -> row));
        assertEquals(Set.of(8, 10), byLine.keySet(), api + ": original query locations");
        assertEquals("NON_LITERAL_QUERY", byLine.get(8).getReasonCode(), api + ": constant query");
        assertEquals("UNSUPPORTED_QUERY_FORM", byLine.get(10).getReasonCode(), api + ": native CTE");
        assertEquals(8, byLine.get(8).getLine());
        assertEquals(3, byLine.get(8).getColumn());
        assertEquals(path, byLine.get(8).getSourcePath());
        assertEquals(MigrateNamedQueryToStatement.class.getName(), byLine.get(8).getRecipe());
        assertTrue(byLine.get(8).getSubject().contains("constant"));
        for (String comment : List.of("first", "between", "end", "later")) assertEquals(1, occurrences(result.text(), "/*" + comment + "*/"), result.text());
        assertTrue(result.text().matches("(?s).*// unrelated\\s+String value = \"hello\";.*"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"orm74", "jpa30", "jpa31", "jpa32"})
    void queryGuards(String api) {
        List<QueryGuardCase> cases = queryGuardCases();
        String directory = "fixture/jpa4/namedqueryconversion/queryguards/";
        var sources = MigrationSources.configured().scenario(directory);
        var result = ApiValidation.run(new MigrateNamedQueryToStatement(), sources, api);
        assertEquals(sources, result.files(), api + ": unsupported queries must remain unchanged");
        assertEquals(cases.size(), result.skipped().size(), api + ": exact number of candidate reports");
        var byPath = result.skipped().stream().collect(java.util.stream.Collectors.groupingBy(row -> row.getSourcePath()));
        assertEquals(sources.keySet(), byPath.keySet(), api + ": reports must identify the expected sources");
        for (QueryGuardCase guard : cases) {
            String context = api + " / " + guard.name();
            var rows = byPath.get(directory + guard.name() + ".java");
            assertEquals(1, rows.size(), context + ": one diagnostic per candidate");
            assertEquals(guard.reason(), rows.getFirst().getReasonCode(), context);
        }
    }

    private record QueryGuardCase(String name, String reason) {}

    private static List<QueryGuardCase> queryGuardCases() {
        return List.of(
                new QueryGuardCase("KeywordSuffix", "UNSUPPORTED_QUERY_FORM"),
                new QueryGuardCase("UnderscoreSuffix", "UNSUPPORTED_QUERY_FORM"),
                new QueryGuardCase("DigitSuffix", "UNSUPPORTED_QUERY_FORM"),
                new QueryGuardCase("DollarSuffix", "UNSUPPORTED_QUERY_FORM"),
                new QueryGuardCase("UnicodeSuffix", "UNSUPPORTED_QUERY_FORM"),
                new QueryGuardCase("SupplementarySuffix", "UNSUPPORTED_QUERY_FORM"),
                new QueryGuardCase("CommonTableExpression", "UNSUPPORTED_QUERY_FORM"),
                new QueryGuardCase("LeadingBlockComment", "UNSUPPORTED_QUERY_FORM"),
                new QueryGuardCase("LeadingLineComment", "UNSUPPORTED_QUERY_FORM"),
                new QueryGuardCase("EmptyQuery", "UNSUPPORTED_QUERY_FORM"),
                new QueryGuardCase("ReturningClause", "NATIVE_RESULT_CLAUSE"),
                new QueryGuardCase("OutputClause", "NATIVE_RESULT_CLAUSE"),
                new QueryGuardCase("MultipleStatements", "UNSUPPORTED_NATIVE_SYNTAX"),
                new QueryGuardCase("RepeatedSemicolon", "UNSUPPORTED_NATIVE_SYNTAX"),
                new QueryGuardCase("UnterminatedQuote", "UNSUPPORTED_NATIVE_SYNTAX"),
                new QueryGuardCase("UnterminatedComment", "UNSUPPORTED_NATIVE_SYNTAX"),
                new QueryGuardCase("NestedComment", "UNSUPPORTED_NATIVE_SYNTAX"),
                new QueryGuardCase("DollarQuote", "UNSUPPORTED_NATIVE_SYNTAX"),
                new QueryGuardCase("OracleQuote", "UNSUPPORTED_NATIVE_SYNTAX"),
                new QueryGuardCase("BackslashQuote", "UNSUPPORTED_NATIVE_SYNTAX"),
                new QueryGuardCase("Concatenation", "NON_LITERAL_QUERY"),
                new QueryGuardCase("ParenthesizedLiteral", "NON_LITERAL_QUERY"),
                new QueryGuardCase("ExplicitLockMode", "INCOMPATIBLE_ANNOTATION_ATTRIBUTES"),
                new QueryGuardCase("ResultAttributesBeforeReturningClause", "INCOMPATIBLE_ANNOTATION_ATTRIBUTES")
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"orm74", "jpa30", "jpa31", "jpa32"})
    void nativeLexicalPositiveAndTextBlocks(String api) {
        var sources = MigrationSources.configured().scenario("fixture/jpa4/namedqueryconversion/nativelexicalpositiveandtextblocks");
        var result = ApiValidation.run(new MigrateNamedQueryToStatement(), sources, api);
        assertTrue(result.skipped().isEmpty());
        for (var entry : result.files().entrySet()) {
            assertEquals(1, occurrences(entry.getValue(), "statement ="), api + " / " + entry.getKey() + "\n" + entry.getValue());
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
        var sourceFiles = MigrationSources.configured().files("fixture/jpa4/namedqueryconversion/namedquerytypeparameterconflicts/Example.java");
        String source = sourceFiles.get("fixture/jpa4/namedqueryconversion/namedquerytypeparameterconflicts/Example.java");
        var result = ApiValidation.run(new MigrateNamedQueryToStatement(), sourceFiles, api);
        assertTrue(result.text().contains("@jakarta.persistence.NamedStatement("));
        assertTrue(result.text().contains("@jakarta.persistence.NamedNativeStatement("));
        assertTrue(result.text().contains("class Example<NamedStatement>"));
        assertTrue(result.text().contains("class NativeExample<NamedNativeStatement>"));
        assertTrue(result.skipped().isEmpty());
    }

    @Test
    void explicitJpa32ResultClassIsAConflict() {
        String path = "fixture/jpa4/namedqueryconversion/explicitjpa32resultclassisaconflict/Example.java";
        var sourceFiles = MigrationSources.configured().files(path);
        String source = sourceFiles.get(path);
        var result = ApiValidation.run(new MigrateNamedQueryToStatement(), sourceFiles, "jpa32");
        assertEquals(source, result.text());
        assertEquals("INCOMPATIBLE_ANNOTATION_ATTRIBUTES", result.skipped().getFirst().getReasonCode());
    }

    @Test
    void crlfConversionPreservesLineEndings() {
        String path = "fixture/jpa4/namedqueryconversion/crlfconversionpreserveslineendings/Example.java";
        var sourceFiles = MigrationSources.configured().files(path);
        String source = sourceFiles.get(path);
        var result = ApiValidation.run(new MigrateNamedQueryToStatement(), sourceFiles, "jpa32");
        assertFalse(result.text().replace("\r\n", "").contains("\n"));
        assertTrue(result.skipped().isEmpty());
    }

    @Test
    void identicalCandidatesAtDifferentLocationsAndCrLf() {
        String path = "fixture/jpa4/namedqueryconversion/identicalcandidatesatdifferentlocationsandcrlf/Example.java";
        var sourceFiles = MigrationSources.configured().files(path);
        String source = sourceFiles.get(path);
        var result = ApiValidation.run(new MigrateNamedQueryToStatement(), sourceFiles, "jpa32");
        assertEquals(source, result.text());
        assertEquals(List.of(5, 9), result.skipped().stream().map(r -> r.getLine()).sorted().toList());
        assertTrue(result.skipped().stream().allMatch(r -> r.getColumn() == 1));
    }

    static int occurrences(String s, String part) { return (s.length() - s.replace(part, "").length()) / part.length(); }
}
