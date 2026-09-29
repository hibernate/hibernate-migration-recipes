/* SPDX-License-Identifier: Apache-2.0 */
package org.hibernate.migration.recipes.jpa4;

import org.hibernate.migration.recipes.table.SkippedMigrations;
import org.openrewrite.*;
import org.openrewrite.config.Environment;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.J;
import org.openrewrite.test.TypeValidation;
import org.openrewrite.tree.ParseError;

import javax.tools.*;
import java.net.URI;
import java.nio.file.*;
import java.io.File;
import java.util.*;
import java.util.stream.*;

import static org.junit.jupiter.api.Assertions.*;

/// Compiles actual source and emitted output with deliberately disjoint API classpaths.
/// @author Steve Ebersole
final class ApiValidation {
    private ApiValidation() {}
    static org.hibernate.migration.testing.ValidationEnvironment environments() { return org.hibernate.migration.testing.ValidationEnvironment.configured(); }
    static List<Path> paths(String profile) { return environments().paths(profile); }
    static JavaParser.Builder<?, ?> parser(String api) { return JavaParser.fromJavaVersion().classpath(paths(api)); }
    static Recipe composite(String name) { return Environment.builder().scanRuntimeClasspath("org.hibernate.migration.recipes").build().activateRecipes(name); }

    static void compile(Map<String, String> files, String api) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            Path output = Files.createTempDirectory("hibernate-recipe-compile-");
            List<JavaFileObject> sources = files.entrySet().stream().map(e -> (JavaFileObject) new SimpleJavaFileObject(URI.create("string:///" + e.getKey()), JavaFileObject.Kind.SOURCE) {
                @Override public CharSequence getCharContent(boolean ignored) { return e.getValue(); }
            }).toList();
            List<String> options = List.of("-proc:none", "--release", environments().value(api + ".javaVersion"), "-classpath", paths(api).stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator)), "-d", output.toString());
            boolean success = compiler.getTask(null, manager, diagnostics, options, null, sources).call();
            try (Stream<Path> tree = Files.walk(output)) {
                for (Path path : tree.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
            assertTrue(success, () -> diagnostics.getDiagnostics().toString() + "\n" + files);
        }
        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }

    static void verifyBaseline(org.openrewrite.test.RecipeSpec spec) {
        spec.afterRecipe(execution -> {
            Map<String, String> sources = new LinkedHashMap<>();
            Map<String, String> emitted = new LinkedHashMap<>();
            for (Result change : execution.getChangeset().getAllResults()) {
                if (change.getBefore() instanceof J.CompilationUnit) {
                    sources.put(change.getBefore().getSourcePath().toString(), change.getBefore().printAll());
                    emitted.put(change.getAfter().getSourcePath().toString(), change.getAfter().printAll());
                }
            }
            if (!sources.isEmpty()) {
                compile(emitted, ApiValidation.environments().primary().target());
                for (String api : List.of("orm74", "jpa30", "jpa31", "jpa32")) {
                    run(spec.getRecipe(), sources, environments().context(api));
                }
            }
        });
    }

    static Outcome run(Recipe recipe, Map<String, String> sources, org.hibernate.migration.testing.ValidationEnvironment.Context context) {
        String api = context.input();
        compile(sources, api);
        List<Throwable> errors = new ArrayList<>();
        var ctx = new InMemoryExecutionContext(errors::add);
        var parser = parser(api).build();
        List<Parser.Input> inputs = sources.entrySet().stream().map(e -> new Parser.Input(Path.of(e.getKey()),
                () -> new java.io.ByteArrayInputStream(e.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8)))).toList();
        List<SourceFile> before = parser.parseInputs(inputs, null, ctx).toList();
        for (SourceFile source : before) {
            assertFalse(source instanceof ParseError, source.printAll());
            org.openrewrite.java.Assertions.validateTypes(source, TypeValidation.all());
        }
        RecipeRun run = recipe.run(new InMemoryLargeSourceSet(before), ctx);
        Map<String, String> output = new LinkedHashMap<>(sources);
        for (Result result : run.getChangeset().getAllResults()) {
            assertNotNull(result.getAfter());
            org.openrewrite.java.Assertions.validateTypes(result.getAfter(), TypeValidation.all());
            output.put(result.getAfter().getSourcePath().toString(), result.getAfter().printAll());
        }
        assertTrue(errors.isEmpty(), errors.toString());
        compile(output, context.target());
        // A fresh parser and execution context establish real target-side idempotence.
        List<SourceFile> target = parser(context.target()).build().parse(new InMemoryExecutionContext(errors::add), output.values().toArray(String[]::new)).toList();
        for (SourceFile file : target) {
            assertInstanceOf(J.CompilationUnit.class, file);
            org.openrewrite.java.Assertions.validateTypes(file, TypeValidation.all());
        }
        RecipeRun second = recipe.run(new InMemoryLargeSourceSet(target), new InMemoryExecutionContext(errors::add));
        assertEquals(0, second.getChangeset().size(), "Fresh-target second run changed sources");
        assertTrue(errors.isEmpty(), errors.toString());
        if (run.getDataTableRows(SkippedMigrations.class).isEmpty()) assertTrue(second.getDataTableRows(SkippedMigrations.class).isEmpty(), "Migrated inputs must not report on a fresh target run");
        return new Outcome(output, run.getDataTableRows(SkippedMigrations.class));
    }

    static Outcome run(Recipe recipe, Map<String, String> sources, String api) { return run(recipe, sources, environments().context(api)); }

    static Outcome run(Recipe recipe, String source, String api) { return run(recipe, Map.of("Example.java", source), environments().context(api)); }
    record Outcome(Map<String, String> files, List<SkippedMigrations.Row> skipped) {
        String text() { return String.join("\n", files.values()); }
    }
}
