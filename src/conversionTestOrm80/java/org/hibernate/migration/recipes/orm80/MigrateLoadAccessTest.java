package org.hibernate.migration.recipes.orm80;

import java.util.List;
import org.hibernate.migration.testing.ApiValidation;
import org.hibernate.migration.testing.MigrationSources;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/// Verifies loading conversion with source/target compilation and fresh-parser idempotence.
/// @author Steve Ebersole
class MigrateLoadAccessTest {
    @Test
    void supportedFamiliesAndOptions() {
        var result = ApiValidation.run(new MigrateLoadAccess(), MigrationSources.configured().files("fixture/loadaccess/Examples.java"), "orm74");
        assertTrue(result.skipped().isEmpty(), result.skipped().stream().map(r -> r.getReasonCode() + ": " + r.getMessage()).toList().toString());
        String text = result.text();
        assertFalse(text.contains(".byId("), text);
        for (String factory : List.of("byMultipleIds", "byNaturalId", "bySimpleNaturalId", "byMultipleNaturalId")) {
            assertFalse(text.contains("." + factory + "("), text);
        }
        assertTrue(text.contains("FindMultipleOption.SessionCheckMode.DISABLED"), text);
        assertTrue(text.contains("ReadOnlyMode.READ_WRITE"), text);
        assertTrue(text.contains("java.util.Optional.ofNullable("), text);
        assertTrue(text.contains("java.util.Collections.singletonMap("), text);
        assertTrue(text.contains("java.util.List.of(ids)"), text);
        assertTrue(text.contains("/*key*/"), text);
    }

    @Test
    void skippedCandidatesStayByteForByteAndReportOriginalPositions() {
        var sources = MigrationSources.configured().files("fixture/loadaccess/Skipped.java");
        var result = ApiValidation.run(new MigrateLoadAccess(), sources, "orm74");
        assertEquals(sources, result.files());
        assertEquals(25, result.skipped().size(), result.skipped().stream().map(r -> r.getLine() + ":" + r.getReasonCode()).toList().toString());
        var counts = result.skipped().stream().collect(java.util.stream.Collectors.groupingBy(
                r -> r.getReasonCode(), java.util.stream.Collectors.counting()));
        assertEquals(1L, counts.get("LOAD_ACCESS_IDENTIFIER_REFERENCE"));
        assertEquals(2L, counts.get("LOAD_ACCESS_NATURAL_REFERENCE"));
        assertEquals(2L, counts.get("LOAD_ACCESS_ENTITY_NAME"));
        assertEquals(3L, counts.get("LOAD_ACCESS_ESCAPED_BUILDER"));
        assertEquals(10L, counts.get("LOAD_ACCESS_UNSUPPORTED_CONFIGURATION"));
        assertEquals(3L, counts.get("LOAD_ACCESS_UNSUPPORTED_NATURAL_KEY"));
        assertEquals(3L, counts.get("LOAD_ACCESS_EVALUATION_ORDER"));
        assertEquals(1L, counts.get("LOAD_ACCESS_UNSUPPORTED_CONTEXT"));
        String source = sources.values().iterator().next();
        for (var row : result.skipped()) {
            assertEquals("fixture/loadaccess/Skipped.java", row.getSourcePath());
            String line = source.lines().toList().get(row.getLine() - 1);
            assertTrue(line.substring(row.getColumn() - 1).startsWith("s"), line);
            assertTrue(row.getRecipe().startsWith("org.hibernate.migration.recipes.orm80.MigrateBy"));
        }
    }

    @Test
    void recipesAreDiscoverableAndComprehensiveOnlyAndComposeWithOptionRelocation() {
        var sources = MigrationSources.configured().files("fixture/loadaccess/Examples.java");
        var requiredRecipe = ApiValidation.composite("org.hibernate.migration.recipes.orm80-required");
        var fullRecipe = ApiValidation.composite("org.hibernate.migration.recipes.orm80");
        var required = ApiValidation.run(requiredRecipe, sources, "orm74");
        assertEquals(sources, required.files());
        var full = ApiValidation.run(fullRecipe, sources, "orm74");
        var staged = ApiValidation.run(fullRecipe, required.files(), "orm80");
        assertEquals(full.files(), staged.files());
        assertTrue(full.skipped().isEmpty());
        for (String leaf : List.of("ById", "ByMultipleIds", "ByNaturalId", "BySimpleNaturalId", "ByMultipleNaturalId")) {
            var recipe = ApiValidation.composite("org.hibernate.migration.recipes.orm80.Migrate" + leaf);
            assertTrue(recipe.validate().isValid());
            assertTrue(ApiValidation.run(recipe, sources, "orm74").skipped().isEmpty());
        }
    }

    @Test
    void partialAttributionReportsOnceWithoutEditingAndUnrelatedSpellingIsIgnored() {
        String source = """
                import org.hibernate.Session;
                class Example { Object load(Session s) { return s.byId(Example.class).load(1L); } }
                """;
        var ctx = new org.openrewrite.InMemoryExecutionContext(t -> { throw new AssertionError(t); });
        var parsed = ApiValidation.parser("orm74").build().parse(ctx, source).findFirst().orElseThrow();
        var stripped = (org.openrewrite.SourceFile) new org.openrewrite.java.JavaIsoVisitor<Integer>() {
            @Override
            public org.openrewrite.java.tree.J.MethodInvocation visitMethodInvocation(org.openrewrite.java.tree.J.MethodInvocation m, Integer p) {
                var call = super.visitMethodInvocation(m, p);
                return m.getSimpleName().equals("load") ? call.withMethodType(null).withName(call.getName().withType(null)) : call;
            }
        }.visit(parsed, 0);
        for (int attempt = 0; attempt < 2; attempt++) {
            var run = new MigrateLoadAccess().run(new org.openrewrite.internal.InMemoryLargeSourceSet(List.of(stripped)), new org.openrewrite.InMemoryExecutionContext(t -> { throw new AssertionError(t); }));
            assertEquals(0, run.getChangeset().size());
            var rows = run.getDataTableRows(org.hibernate.migration.recipes.table.SkippedMigrations.class);
            assertEquals(1, rows.size());
            assertEquals("LOAD_ACCESS_UNRESOLVED_TYPE", rows.get(0).getReasonCode());
        }
        String unrelated = "class Example { Example byId(Class<?> c) { return this; } Object load(Object key) { return key; } Object use() { return byId(Example.class).load(1L); } }";
        var result = ApiValidation.run(new MigrateLoadAccess(), unrelated, "orm74");
        assertEquals(unrelated, result.text());
        assertTrue(result.skipped().isEmpty());
    }

    @Test
    void inheritedCallsArrayWitnessesConflictsAndComments() {
        var sources = MigrationSources.configured().files("fixture/loadaccess/Advanced.java");
        var result = ApiValidation.run(new MigrateLoadAccess(), sources, "orm74");
        assertTrue(result.skipped().isEmpty(), result.skipped().stream().map(r -> r.getReasonCode() + ": " + r.getMessage()).toList().toString());
        assertTrue(result.text().contains("java.util.List.<Object[]>of(keys)"), result.text());
        assertTrue(result.text().contains("java.util.List.of(key)"), result.text());
        for (String comment : List.of("/*flag*/", "/*batch*/")) {
            assertEquals(1, result.text().split(java.util.regex.Pattern.quote(comment), -1).length - 1);
        }
        var crlf = new java.util.LinkedHashMap<String, String>();
        sources.forEach((path, value) -> crlf.put(path, value.replace("\n", "\r\n")));
        String text = ApiValidation.run(new MigrateLoadAccess(), crlf, "orm74").text();
        assertTrue(text.contains("\r\n"));
        assertFalse(text.replace("\r\n", "").contains("\n"));
    }

    @Test
    void exactBasicOutput() {
        String source = "import org.hibernate.Session; class Example { Object read(Session s, Long id) { return s.byId(Example.class).load(id); } }";
        assertEquals("import org.hibernate.Session; class Example { Object read(Session s, Long id) { return s.find(Example.class, id, org.hibernate.LockMode.NONE); } }",
                ApiValidation.run(new MigrateById(), source, "orm74").text());
    }

    @Test
    void loadAccessAndBulkOptionRelocationCommute() {
        String source = """
                import org.hibernate.Session;
                import java.util.List;
                class Example {
                    List<Example> fluent(Session s, List<Long> keys) { return s.byMultipleIds(Example.class).multiLoad(keys); }
                    List<Example> existing(Session s, List<Long> keys) { return s.findMultiple(Example.class, keys, org.hibernate.OrderingMode.UNORDERED); }
                }
                """;
        java.util.Map<String, String> previous = null;
        for (boolean optionsFirst : List.of(true, false)) {
            org.openrewrite.Recipe combined = new org.openrewrite.Recipe() {
                @Override public String getDisplayName() { return "Test loading composition"; }
                @Override public String getDescription() { return "Checks both supported recipe orders."; }
                @Override public List<org.openrewrite.Recipe> getRecipeList() {
                    return optionsFirst ? List.of(new MigrateFindMultipleOptions(), new MigrateLoadAccess())
                            : List.of(new MigrateLoadAccess(), new MigrateFindMultipleOptions());
                }
            };
            var result = ApiValidation.run(combined, source, "orm74");
            assertTrue(result.skipped().isEmpty());
            if (previous != null) assertEquals(previous, result.files());
            previous = result.files();
        }
    }

    @Test
    void inheritedPackageShadowAcrossFilesIsReportedInsteadOfGeneratingInvalidNames() {
        var sources = java.util.Map.of(
                "p/Base.java", "package p; class Base { static class org {} }",
                "p/Example.java", "package p; import org.hibernate.Session; class Example extends Base { Object read(Session s) { return s.byId(Example.class).load(1L); } }");
        var result = ApiValidation.run(new MigrateLoadAccess(), sources, "orm74");
        assertEquals(sources, result.files());
        assertEquals("LOAD_ACCESS_UNSUPPORTED_CONTEXT", result.skipped().getFirst().getReasonCode());
    }

    @Test
    void overloadsExplicitNullsAndDuplicateCommentsAreRetainedAndReasonsHaveStablePrecedence() {
        String source = """
                import org.hibernate.Session;
                import org.hibernate.CacheMode;
                interface Custom extends Session {
                    default Example find(Class<Example> c, Object id, org.hibernate.LockMode mode) { return null; }
                }
                class Example {
                    Object overloaded(Custom s) { return s.byId(Example.class).load(1L); }
                    Object nullType(Session s) { return s.byId((Class<Example>) null).load(1L); }
                    Object nullKey(Session s) { return s.byId(Example.class).load(null); }
                    Object nullArray(Session s) { return s.byMultipleIds(Example.class).multiLoad((Object[]) null); }
                    Object precedence(Session s, CacheMode mode) { return s.byId(Example.class).with(mode).withReadOnly(true).withReadOnly(false).load(1L); }
                    Object comments(Session s) { return s.byId(Example.class)/*same*/.load(/*same*/ 1L); }
                }
                """;
        var result = ApiValidation.run(new MigrateLoadAccess(), source, "orm74");
        assertEquals(source, result.text());
        assertEquals(6, result.skipped().size());
        assertEquals(5, result.skipped().stream().filter(r -> r.getReasonCode().equals("LOAD_ACCESS_UNSUPPORTED_CONTEXT")).count());
        assertEquals(1, result.skipped().stream().filter(r -> r.getReasonCode().equals("LOAD_ACCESS_UNSUPPORTED_CONFIGURATION")).count());
    }
}
