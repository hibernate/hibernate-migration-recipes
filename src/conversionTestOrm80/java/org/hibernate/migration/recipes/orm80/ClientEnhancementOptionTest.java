package org.hibernate.migration.recipes.orm80;

import org.hibernate.migration.recipes.table.SkippedMigrations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openrewrite.*;
import org.openrewrite.groovy.GroovyParser;
import org.openrewrite.groovy.tree.G;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.kotlin.KotlinParser;
import org.openrewrite.kotlin.tree.K;
import org.openrewrite.xml.XmlParser;
import org.openrewrite.xml.tree.Xml;

import java.nio.file.Path;
import java.util.*;

import static org.hibernate.migration.recipes.support.EnhancementMigrationSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/// Exercises build-file conversion, bounded recognition, diagnostics, and fresh-parser idempotence.
///
/// @author Steve Ebersole
class ClientEnhancementOptionTest {
    private record Outcome(String text, List<SkippedMigrations.Row> rows) {}

    private Outcome run(String path, String input) {
        var ctx = new InMemoryExecutionContext(error -> { throw new AssertionError(error); });
        SourceFile source = parse(path, input, ctx);
        Recipe recipe = new MigrateClientEnhancementOption();
        var run = recipe.run(new InMemoryLargeSourceSet(List.of(source)), ctx);
        var changes = run.getChangeset().getAllResults();
        String output = changes.isEmpty() ? input : Objects.requireNonNull(changes.getFirst().getAfter()).printAll();
        var reparsed = parse(path, output, ctx);
        var second = new MigrateClientEnhancementOption().run(new InMemoryLargeSourceSet(List.of(reparsed)),
                new InMemoryExecutionContext(error -> { throw new AssertionError(error); })).getChangeset().getAllResults();
        assertTrue(second.isEmpty(), () -> "Second run changed build file: " + second.stream().map(r -> r.getRecipes() + ":" + r.diff()).toList());
        return new Outcome(output, run.getDataTableRows(SkippedMigrations.class));
    }

    private SourceFile parse(String path, String text, ExecutionContext ctx) {
        Parser parser = path.endsWith(".gradle.kts") ? KotlinParser.builder().isKotlinScript(true).build()
                : path.endsWith(".gradle") ? GroovyParser.builder().build() : XmlParser.builder().build();
        SourceFile source = parser.parse(ctx, text).findFirst().orElseThrow().withSourcePath(Path.of(path));
        Class<?> expected = path.endsWith(".kts") ? K.CompilationUnit.class : path.endsWith(".gradle") ? G.CompilationUnit.class : Xml.Document.class;
        assertInstanceOf(expected, source);
        assertEquals(text, source.printAll(), "Parser must preserve input");
        return source;
    }

    private String gradle(String option) {
        return "plugins { id(\"org.hibernate.orm\") }\nhibernate {\n    enhancement {\n        " + option + "\n    }\n}\n";
    }

    private String pom(String configuration) {
        return "<project><build><plugins><plugin><groupId>org.hibernate.orm</groupId><artifactId>hibernate-maven-plugin</artifactId>"
                + "<configuration>" + configuration + "</configuration></plugin></plugins></build></project>";
    }

    private String ant(String invocation) {
        return "<project><taskdef name=\"instrument\" classname=\"org.hibernate.tool.enhance.EnhancementTask\"/>"
                + "<target name=\"enhance\">" + invocation + "</target></project>";
    }

    @ParameterizedTest @ValueSource(strings = {"build.gradle", "build.gradle.kts"})
    void gradleAssignmentsAndSetPreserveExpressions(String path) {
        for (String statement : List.of(OLD + " = true", OLD + " = false", OLD + ".set(false)",
                "this." + OLD + ".set(true)", "this." + OLD + " = false", OLD + ".set(provider { true })")) {
            String input = gradle(statement);
            Outcome outcome = run(path, input);
            assertEquals(input.replace(OLD, NEW), outcome.text);
            assertTrue(outcome.rows.isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"build.gradle", "build.gradle.kts"})
    void gradleNewValueWinsInEitherOrder(String path) {
        for (String statements : List.of(OLD + " = true\n        " + NEW + " = false",
                NEW + ".set(provider { false })\n        " + OLD + ".set(false)")) {
            Outcome outcome = run(path, gradle(statements));
            assertFalse(outcome.text.contains(OLD));
            assertTrue(outcome.text.contains(statements.substring(statements.indexOf(NEW)).split("\n")[0]));
            assertTrue(outcome.rows.isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"build.gradle", "build.gradle.kts"})
    void gradleUnsupportedAndAmbiguousConfiguration(String path) {
        for (String statement : List.of("if (true) { " + OLD + ".set(true) }", OLD + ".convention(true)",
                OLD + " = true\n        println(" + OLD + ")", OLD + ".set(provider { true })\n        " + NEW + " = false")) {
            String input = gradle(statement);
            Outcome outcome = run(path, input);
            assertEquals(input, outcome.text);
            assertFalse(outcome.rows.isEmpty());
            assertTrue(outcome.rows.stream().allMatch(row -> SYNTAX.equals(row.getReasonCode())));
        }
        String duplicates = gradle(OLD + " = true\n        " + OLD + " = false");
        assertSkipped(path, duplicates, AMBIGUOUS, 2);
        String blocks = gradle(OLD + " = true") + "hibernate { enhancement { " + NEW + " = false } }\n";
        assertSkipped(path, blocks, AMBIGUOUS, 1);
    }

    @ParameterizedTest @ValueSource(strings = {"build.gradle", "build.gradle.kts"})
    void gradleIdentityAndUnrelatedOptions(String path) {
        String missingPlugin = gradle(OLD + " = true").replace("plugins { id(\"org.hibernate.orm\") }\n", "");
        assertSkipped(path, missingPlugin, IDENTITY, 1);
        String unrelated = "other { " + OLD + " = false }\n";
        Outcome outcome = run(path, unrelated);
        assertEquals(unrelated, outcome.text); assertTrue(outcome.rows.isEmpty());
        for (String value : List.of(NEW + " = false", "")) {
            String input = gradle(value);
            assertEquals(input, run(path, input).text);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"build.gradle", "build.gradle.kts"})
    void gradleCrLfCommentsAndCoordinates(String path) {
        String input = gradle("// obsolete comment\n        " + OLD + " = true\n        // current comment\n        " + NEW + " = false").replace("\n", "\r\n");
        Outcome outcome = run(path, input);
        assertFalse(outcome.text.contains(OLD));
        assertTrue(outcome.text.contains("// obsolete comment")); assertTrue(outcome.text.contains("// current comment"));
        assertFalse(outcome.text.replace("\r\n", "").contains("\n"));
        String skipped = gradle("// " + OLD + " in a comment\n        " + OLD + ".convention(true)").replace("\n", "\r\n");
        var row = run(path, skipped).rows.getFirst();
        assertEquals(5, row.getLine()); assertEquals(9, row.getColumn()); assertEquals(path, row.getSourcePath());
        assertEquals(MigrateGradleClientEnhancementOption.class.getName(), row.getRecipe());
    }

    @ParameterizedTest @ValueSource(strings = {"build.gradle", "build.gradle.kts"})
    void gradlePluginApplicationAndVersionSyntax(String path) {
        String input = gradle(OLD + " = false");
        String versioned = input.replace("id(\"org.hibernate.orm\")", "id(\"org.hibernate.orm\") version \"8.0.0.Beta3\"");
        assertEquals(versioned.replace(OLD, NEW), run(path, versioned).text);
        String application = path.endsWith(".kts") ? "apply(plugin = \"org.hibernate.orm\")" : "apply plugin: 'org.hibernate.orm'";
        String applied = input.replace("plugins { id(\"org.hibernate.orm\") }", application);
        assertEquals(applied.replace(OLD, NEW), run(path, applied).text);
    }

    @Test void mavenInternalCommentsAndForeignNamespaces() {
        String input = pom("<" + OLD + "><!-- retain me -->true</" + OLD + "><" + NEW + ">false</" + NEW + ">");
        Outcome outcome = run("pom.xml", input);
        assertFalse(outcome.text.contains(OLD)); assertTrue(outcome.text.contains("<!-- retain me -->"));
        String foreign = pom("<" + OLD + " xmlns=\"urn:other\">true</" + OLD + ">");
        assertEquals(foreign, run("pom.xml", foreign).text); assertTrue(run("pom.xml", foreign).rows.isEmpty());
        String prefixed = "<m:project xmlns:m=\"http://maven.apache.org/POM/4.0.0\"><m:build><m:plugins><m:plugin><m:groupId>org.hibernate.orm</m:groupId><m:artifactId>hibernate-maven-plugin</m:artifactId><m:configuration><m:" + OLD + ">true</m:" + OLD + "></m:configuration></m:plugin></m:plugins></m:build></m:project>";
        assertEquals(prefixed.replace(OLD, NEW), run("pom.xml", prefixed).text);
    }

    @Test void antImportedAndResourceDefinitionsAreUnresolved() {
        String input = "<project><import file=\"tasks.xml\"/><instrument " + OLD + "='true'/></project>";
        assertSkipped("build.xml", input, IDENTITY, 1);
        String resource = "<project><taskdef name=\"instrument\" resource=\"tasks.properties\"/><instrument " + OLD + "='true'/></project>";
        assertSkipped("build.xml", resource, IDENTITY, 1);
    }

    @ParameterizedTest @ValueSource(strings = {"build.gradle", "build.gradle.kts"})
    void gradleDoesNotAssumePluginOrProjectIdentity(String path) {
        String input = gradle(OLD + " = true");
        String notApplied = input.replace("id(\"org.hibernate.orm\")", "id(\"org.hibernate.orm\").apply(false)");
        assertSkipped(path, notApplied, IDENTITY, 1);
        String children = input.substring(0, input.indexOf("hibernate {")) + "subprojects {\n" + input.substring(input.indexOf("hibernate {")) + "}\n";
        assertSkipped(path, children, IDENTITY, 1);
    }

    @Test void antMacroBodiesAreNotMigrated() {
        String input = ant("<instrument " + OLD + "='true'/>").replace("<target name=\"enhance\">", "<macrodef name=\"wrapped\"><sequential>").replace("</target>", "</sequential></macrodef>");
        assertSkipped("build.xml", input, SYNTAX, 1);
    }

    @Test void compositeMigratesMixedBuildDocuments() {
        var ctx = new InMemoryExecutionContext(error -> { throw new AssertionError(error); });
        Map<String, String> inputs = Map.of("gradle/build.gradle", gradle(OLD + " = true"),
                "kotlin/build.gradle.kts", gradle(OLD + ".set(false)"), "maven/pom.xml", pom("<" + OLD + ">${clients}</" + OLD + ">"),
                "ant/build.xml", ant("<instrument " + OLD + "='false'/>"));
        List<SourceFile> sources = inputs.entrySet().stream().map(entry -> parse(entry.getKey(), entry.getValue(), ctx)).toList();
        var result = new MigrateClientEnhancementOption().run(new InMemoryLargeSourceSet(sources), ctx);
        assertEquals(4, result.getChangeset().size());
        for (var change : result.getChangeset().getAllResults()) {
            SourceFile after = Objects.requireNonNull(change.getAfter());
            assertEquals(inputs.get(after.getSourcePath().toString()).replace(OLD, NEW), after.printAll());
        }
        assertTrue(result.getDataTableRows(SkippedMigrations.class).isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {"build.gradle", "build.gradle.kts"})
    void gradleRetainsCommentsOnRemovedFinalStatement(String path) {
        String input = gradle(NEW + " = false\n        // keep obsolete explanation\n        " + OLD + ".set(true)");
        Outcome outcome = run(path, input);
        assertFalse(outcome.text.contains(OLD));
        assertTrue(outcome.text.contains("// keep obsolete explanation"), outcome.text);
    }

    @Test void xmlLineEndingsAndUnresolvedAntNamespaces() {
        String maven = pom("<" + OLD + ">false</" + OLD + ">").replace("><", ">\r\n<");
        assertEquals(maven.replace(OLD, NEW), run("pom.xml", maven).text);
        String ant = ant("<instrument " + OLD + "='${clients}'/>").replace("><", ">\r\n<");
        assertEquals(ant.replace(OLD, NEW), run("build.xml", ant).text);
        String namespaced = "<project xmlns:h=\"antlib:org.hibernate\"><h:enhance " + OLD + "='true'/></project>";
        assertSkipped("build.xml", namespaced, IDENTITY, 1);
    }

    @Test void mavenValuesNamespacesAndPluginAllowlist() {
        for (String value : List.of("true", "false", "${enhance.clients}")) {
            for (String coordinates : List.of("org.hibernate.orm:hibernate-maven-plugin", "org.hibernate.orm:hibernate-enhance-maven-plugin", "org.hibernate:hibernate-enhance-maven-plugin")) {
                String[] parts = coordinates.split(":");
                String input = pom("<" + OLD + ">" + value + "</" + OLD + ">")
                        .replace("org.hibernate.orm", parts[0]).replace("hibernate-maven-plugin", parts[1]);
                for (String namespace : List.of("", " xmlns=\"http://maven.apache.org/POM/4.0.0\"")) {
                    String text = input.replace("<project>", "<project" + namespace + ">");
                    Outcome outcome = run("pom.xml", text);
                    assertEquals(text.replace(OLD, NEW), outcome.text); assertTrue(outcome.rows.isEmpty());
                }
            }
        }
        String unrelated = pom("<" + OLD + ">true</" + OLD + ">").replace("org.hibernate.orm", "example");
        assertEquals(unrelated, run("pom.xml", unrelated).text);
        assertTrue(run("pom.xml", unrelated).rows.isEmpty());
    }

    @Test void mavenNewValueWinsAndCommentsSurvive() {
        for (String config : List.of("<!-- old --><" + OLD + ">true</" + OLD + "><!-- new --><" + NEW + ">false</" + NEW + ">",
                "<" + NEW + ">${clients}</" + NEW + "><" + OLD + ">false</" + OLD + ">")) {
            String input = pom(config);
            Outcome outcome = run("pom.xml", input);
            assertEquals(input.replaceAll("<" + OLD + ">[^<]*</" + OLD + ">", ""), outcome.text);
            assertTrue(outcome.rows.isEmpty());
        }
    }

    @Test void mavenScopesAndInheritance() {
        String option = "<" + OLD + ">true</" + OLD + ">";
        for (String input : List.of(pom(option).replace("<plugins>", "<pluginManagement><plugins>").replace("</plugins>", "</plugins></pluginManagement>"),
                pom(option).replace("<build>", "<profiles><profile><id>enhance</id><build>").replace("</build>", "</build></profile></profiles>"),
                pom(option).replace("<configuration>", "<executions><execution><id>enhance</id><configuration>").replace("</configuration>", "</configuration></execution></executions>"))) {
            assertEquals(input.replace(OLD, NEW), run("pom.xml", input).text);
        }
        String parent = pom(option).replace("<project>", "<project><parent><groupId>example</groupId><artifactId>parent</artifactId><version>1</version></parent>");
        assertSkipped("pom.xml", parent, INHERITANCE, 1);
        String crossScope = pom(option).replace("</plugin>", "<executions><execution><configuration><" + NEW + ">false</" + NEW + "></configuration></execution></executions></plugin>");
        assertSkipped("pom.xml", crossScope, INHERITANCE, 1);
        String both = parent.replace(option, option + "<" + NEW + ">false</" + NEW + ">");
        assertFalse(run("pom.xml", both).text.contains(OLD));
    }

    @Test void mavenRejectionsAndDiagnosticCoordinates() {
        String option = "<" + OLD + ">true</" + OLD + ">";
        assertSkipped("pom.xml", pom(option + option), AMBIGUOUS, 2);
        assertSkipped("pom.xml", pom(option).replace("<configuration>", "<configuration combine.self=\"override\">"), AMBIGUOUS, 1);
        assertSkipped("pom.xml", pom("<" + OLD + "><nested/></" + OLD + ">"), SYNTAX, 1);
        assertSkipped("pom.xml", pom(option).replace("org.hibernate.orm", "${plugin.group}"), IDENTITY, 1);
        String input = pom(option).replace("<configuration>", "<configuration combine.self=\"override\">\r\n  <!-- " + OLD + " -->\r\n  ");
        var row = run("pom.xml", input).rows.getFirst();
        assertEquals(3, row.getLine()); assertEquals(4, row.getColumn());
    }

    @Test void antAliasesValuesAndConflicts() {
        for (String value : List.of("true", "false", "${client.option}")) {
            String input = ant("<instrument " + OLD + "='" + value + "'/>");
            Outcome outcome = run("build.xml", input);
            assertEquals(input.replace(OLD, NEW), outcome.text); assertTrue(outcome.rows.isEmpty());
        }
        for (String attributes : List.of(OLD + "=\"true\" " + NEW + "=\"false\"", NEW + "='${client.option}' " + OLD + "='false'")) {
            String input = ant("<instrument " + attributes + "/>");
            Outcome outcome = run("build.xml", input);
            assertFalse(outcome.text.contains(OLD)); assertTrue(outcome.text.contains(NEW)); assertTrue(outcome.rows.isEmpty());
        }
    }

    @Test void antTaskIdentityAndOriginalLocations() {
        String input = ant("<instrument " + OLD + "=\"true\"/>");
        assertSkipped("build.xml", input.replace("org.hibernate.tool.enhance.EnhancementTask", "${task.class}"), IDENTITY, 1);
        assertSkipped("build.xml", input.replace("<taskdef", "<target name=\"define\" if=\"enabled\"><taskdef").replace("<target name=\"enhance\">", "</target><target name=\"enhance\">"), IDENTITY, 1);
        assertSkipped("build.xml", input.replace("<target name=\"enhance\">", "<taskdef name=\"instrument\" classname=\"example.OtherTask\"/><target name=\"enhance\">"), AMBIGUOUS, 1);
        String other = input.replace("org.hibernate.tool.enhance.EnhancementTask", "example.OtherTask");
        Outcome unrelated = run("build.xml", other);
        assertEquals(other, unrelated.text); assertTrue(unrelated.rows.isEmpty());
        String unknown = "<project>\r\n  <enhance " + OLD + "='true'/>\r\n</project>";
        var row = run("build.xml", unknown).rows.getFirst();
        assertEquals(2, row.getLine()); assertEquals(12, row.getColumn());
    }

    @Test void independentMavenConfigurationsAreIsolated() {
        String old = "<" + OLD + ">true</" + OLD + ">";
        String input = pom(old + old).replace("</plugins>", "<plugin><groupId>org.hibernate</groupId><artifactId>hibernate-enhance-maven-plugin</artifactId><configuration>"
                + old + "</configuration></plugin></plugins>");
        Outcome outcome = run("pom.xml", input);
        assertTrue(outcome.text.contains(old + old));
        assertTrue(outcome.text.contains("<" + NEW + ">true</" + NEW + ">"));
        assertEquals(2, outcome.rows.size());
    }

    private void assertSkipped(String path, String input, String reason, int count) {
        Outcome outcome = run(path, input);
        assertEquals(input, outcome.text);
        assertEquals(count, outcome.rows.size(), outcome.rows.toString());
        for (var row : outcome.rows) {
            assertEquals(reason, row.getReasonCode()); assertEquals(OLD, row.getSubject());
            assertEquals(path, row.getSourcePath()); assertFalse(row.getMessage().isBlank());
        }
    }
}
