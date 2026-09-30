/* SPDX-License-Identifier: Apache-2.0 */
package org.hibernate.migration.testing;

import org.hibernate.migration.recipes.table.SkippedMigrations;
import org.openrewrite.*;
import org.openrewrite.config.Environment;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.J;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.TypeValidation;

import javax.tools.*;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/// Validates complete fixture inputs and actual emitted output against separate API environments.
/// The run methods perform source compilation/parsing, conversion, target compilation/parsing,
/// and a fresh target execution for idempotence. The RewriteTest callback has a narrower,
/// changed-file-only contract documented on [#validateChangedJavaSourcesAfterRecipe(RecipeSpec)].
/// @author Steve Ebersole
public final class ApiValidation {
    private ApiValidation() {}

    public static ValidationEnvironment environments() {
        return ValidationEnvironment.configured();
    }

    public static JavaParser.Builder<?, ?> parser(String api) {
        return JavaParser.fromJavaVersion().classpath(environments().paths(api));
    }

    public static Recipe composite(String name) {
        return Environment.builder().scanRuntimeClasspath("org.hibernate.migration.recipes")
                .build().activateRecipes(name);
    }

    /// Compiles supplied fixtures against the named API environment.
    public static void compile(Map<String, String> files, String api) {
        inPhase("compilation", api, () -> { compileSources(files, api); return null; });
    }

    private static void compileSources(Map<String, String> files, String api) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "A JDK compiler is required");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Path output = Files.createTempDirectory("hibernate-recipe-compile-");
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            List<JavaFileObject> sources = files.entrySet().stream().map(entry ->
                    (JavaFileObject) new SimpleJavaFileObject(URI.create("string:///" + entry.getKey()), JavaFileObject.Kind.SOURCE) {
                        @Override public CharSequence getCharContent(boolean ignored) { return entry.getValue(); }
                    }).toList();
            String classpath = environments().paths(api).stream().map(Path::toString)
                    .collect(Collectors.joining(File.pathSeparator));
            List<String> options = List.of("-proc:none", "--release", environments().value(api + ".javaVersion"),
                    "-classpath", classpath, "-d", output.toString());
            boolean success = compiler.getTask(null, manager, diagnostics, options, null, sources).call();
            assertTrue(success, () -> diagnostics.getDiagnostics() + "\nSources: " + files);
        }
        finally {
            try (Stream<Path> tree = Files.walk(output)) {
                for (Path path : tree.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    /// Adds compilation checks for the changed Java sources exposed by RewriteTest's changeset.
    /// Unchanged-only tests receive no checks here, and unchanged supporting files are unavailable.
    /// Use [#run(Recipe, Map, ValidationEnvironment.Context)] when complete-input compilation is
    /// part of the test's contract. Malformed/attribution-deficient fixtures need raw-parser tests.
    public static void validateChangedJavaSourcesAfterRecipe(RecipeSpec spec) {
        spec.afterRecipe(execution -> {
            Map<String, String> sources = new LinkedHashMap<>();
            Map<String, String> emitted = new LinkedHashMap<>();
            for (Result change : execution.getChangeset().getAllResults()) {
                if (change.getBefore() instanceof J.CompilationUnit) {
                    sources.put(change.getBefore().getSourcePath().toString(), change.getBefore().printAll());
                    assertNotNull(change.getAfter(), "Changed-source validation expects no deleted fixtures");
                    emitted.put(change.getAfter().getSourcePath().toString(), change.getAfter().printAll());
                }
            }
            if (sources.isEmpty()) return;
            compile(emitted, environments().primary().target());
            for (String api : List.of("orm74", "jpa30", "jpa31", "jpa32")) {
                run(spec.getRecipe(), sources, environments().context(api));
            }
        });
    }

    /// Validates every supplied source, including unchanged files needed by other fixtures.
    public static Outcome run(Recipe recipe, Map<String, String> sources, ValidationEnvironment.Context context) {
        String sourceApi = context.input();
        String targetApi = context.target();
        inPhase("source compilation", sourceApi, () -> { compileSources(sources, sourceApi); return null; });
        List<SourceFile> original = inPhase("source parsing and attribution", sourceApi, () -> parseSources(sources, sourceApi));
        RecipeRun converted = inPhase("conversion execution", sourceApi, () -> execute(recipe, original));
        Map<String, String> emitted = inPhase("converted attribution", sourceApi,
                () -> collectOutput(sources, converted));
        inPhase("target compilation", targetApi, () -> { compileSources(emitted, targetApi); return null; });
        List<SourceFile> target = inPhase("target parsing and attribution", targetApi, () -> parseSources(emitted, targetApi));
        inPhase("target idempotence", targetApi, () -> { verifyIdempotence(recipe, target, converted); return null; });
        return new Outcome(emitted, converted.getDataTableRows(SkippedMigrations.class));
    }

    private static List<SourceFile> parseSources(Map<String, String> sources, String api) {
        List<Throwable> errors = new ArrayList<>();
        var ctx = new InMemoryExecutionContext(errors::add);
        List<Parser.Input> inputs = sources.entrySet().stream().map(entry -> new Parser.Input(Path.of(entry.getKey()),
                () -> new ByteArrayInputStream(entry.getValue().getBytes(StandardCharsets.UTF_8)))).toList();
        List<SourceFile> parsed = parser(api).build().parseInputs(inputs, null, ctx).toList();
        assertNoExecutionErrors(errors);
        assertEquals(sources.size(), parsed.size(), "Each supplied file must be parsed");
        for (SourceFile source : parsed) validateAttribution(source);
        return parsed;
    }

    private static RecipeRun execute(Recipe recipe, List<SourceFile> sources) {
        List<Throwable> errors = new ArrayList<>();
        var run = recipe.run(new InMemoryLargeSourceSet(sources), new InMemoryExecutionContext(errors::add));
        assertNoExecutionErrors(errors);
        return run;
    }

    private static Map<String, String> collectOutput(Map<String, String> sources, RecipeRun run) {
        Map<String, String> output = new LinkedHashMap<>(sources);
        for (Result result : run.getChangeset().getAllResults()) {
            SourceFile after = result.getAfter();
            assertNotNull(after, "Conversion must not delete fixture sources");
            validateAttribution(after);
            output.put(after.getSourcePath().toString(), after.printAll());
        }
        return output;
    }

    private static void validateAttribution(SourceFile source) {
        try {
            assertInstanceOf(J.CompilationUnit.class, source, source.printAll());
            org.openrewrite.java.Assertions.validateTypes(source, TypeValidation.all());
        }
        catch (AssertionError failure) {
            throw new AssertionError("Source " + source.getSourcePath() + ": " + failure.getMessage(), failure);
        }
    }

    private static void verifyIdempotence(Recipe recipe, List<SourceFile> target, RecipeRun first) {
        RecipeRun second = execute(recipe, target);
        assertEquals(0, second.getChangeset().size(), "Fresh-target second run changed sources");
        if (first.getDataTableRows(SkippedMigrations.class).isEmpty()) {
            assertTrue(second.getDataTableRows(SkippedMigrations.class).isEmpty(),
                    "Migrated inputs must not report on a fresh target run");
        }
    }

    private static void assertNoExecutionErrors(List<Throwable> errors) {
        if (errors.isEmpty()) return;
        var failure = new AssertionError("Parser/recipe errors: " + errors, errors.getFirst());
        errors.stream().skip(1).forEach(failure::addSuppressed);
        throw failure;
    }

    // Attach context at phase boundaries while keeping the underlying assertion/exception.
    private static <T> T inPhase(String phase, String api, Phase<T> action) {
        try {
            return action.run();
        }
        catch (Exception | AssertionError failure) {
            throw new AssertionError(phase + " [" + api + "]: " + failure.getMessage(), failure);
        }
    }

    @FunctionalInterface
    private interface Phase<T> { T run() throws Exception; }

    public static Outcome run(Recipe recipe, Map<String, String> sources, String api) {
        return run(recipe, sources, environments().context(api));
    }

    public static Outcome run(Recipe recipe, String source, String api) {
        return run(recipe, Map.of("Example.java", source), environments().context(api));
    }

    public record Outcome(Map<String, String> files, List<SkippedMigrations.Row> skipped) {
        public String text() { return String.join("\n", files.values()); }
    }
}
