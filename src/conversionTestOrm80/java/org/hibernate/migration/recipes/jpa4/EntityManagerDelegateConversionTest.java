package org.hibernate.migration.recipes.jpa4;

import org.hibernate.migration.testing.ApiValidation;
import org.hibernate.migration.recipes.table.SkippedMigrations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/// Validates delegate dispatch and name-resolution boundaries against source and target APIs.
/// @author Steve Ebersole
class EntityManagerDelegateConversionTest {
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
        assertReasonsByLine(result.skipped(), Map.of(
                5, "UNSUPPORTED_METHOD_OVERRIDE",
                6, "UNSUPPORTED_METHOD_REFERENCE",
                9, "NAME_RESOLUTION_CONFLICT",
                10, "UNSUPPORTED_METHOD_REFERENCE"), api);
    }

    @Test
    void delegatePackageTypeShadowAndSuperOverride() {
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
        assertReasonsByLine(result.skipped(), Map.of(7, "UNSUPPORTED_METHOD_OVERRIDE", 8, "NAME_RESOLUTION_CONFLICT"), "jpa32");
    }

    @ParameterizedTest
    @ValueSource(strings = {"orm74", "jpa30", "jpa31", "jpa32"})
    void delegateTypeParameterConflicts(String api) {
        String source = """
                import jakarta.persistence.EntityManager;
                class Example<java> {
                    Object call(EntityManager em) { return em.getDelegate(); }
                }
                class MethodExample {
                    <java> Object call(EntityManager em) { return em.getDelegate(); }
                }
                """;
        var result = ApiValidation.run(new MigrateEntityManagerGetDelegate(), source, api);
        assertEquals(source, result.text());
        assertReasonsByLine(result.skipped(), Map.of(3, "NAME_RESOLUTION_CONFLICT", 6, "NAME_RESOLUTION_CONFLICT"), api);
    }

    @ParameterizedTest
    @ValueSource(strings = {"orm74", "jpa30", "jpa31", "jpa32"})
    void delegateInheritedMemberTypeConflictsAcrossFiles(String api) {
        String base = "class Base { static class java {} }";
        String source = """
                import jakarta.persistence.EntityManager;
                class Middle extends Base {}
                class Example extends Middle {
                    Object call(EntityManager em) { return em.getDelegate(); }
                }
                """;
        String unrelated = """
                import jakarta.persistence.EntityManager;
                class Unrelated {
                    Object call(EntityManager em) { return em.getDelegate(); }
                }
                """;
        var sources = new java.util.LinkedHashMap<String, String>();
        // The subtype precedes the member-type declaration to exercise scan-before-edit behavior.
        sources.put("Example.java", source);
        sources.put("Unrelated.java", unrelated);
        sources.put("Base.java", base);
        var result = ApiValidation.run(new MigrateEntityManagerGetDelegate(), sources, api);
        assertEquals(source, result.files().get("Example.java"));
        assertEquals(base, result.files().get("Base.java"));
        assertTrue(result.files().get("Unrelated.java").contains("unwrap(java.lang.Object.class)"));
        assertReasonsByLine(result.skipped(), Map.of(4, "NAME_RESOLUTION_CONFLICT"), api);
    }

    private static void assertReasonsByLine(List<SkippedMigrations.Row> rows, Map<Integer, String> expected, String api) {
        assertEquals(expected.size(), rows.size(), api + ": exact candidate count");
        var byLine = rows.stream().collect(java.util.stream.Collectors.groupingBy(SkippedMigrations.Row::getLine));
        assertEquals(expected.keySet(), byLine.keySet(), api + ": original candidate lines");
        expected.forEach((line, reason) -> {
            var candidates = byLine.get(line);
            String context = api + " / Example.java:" + line;
            assertEquals(1, candidates.size(), context);
            assertEquals("Example.java", candidates.getFirst().getSourcePath(), context);
            assertEquals(reason, candidates.getFirst().getReasonCode(), context);
        });
    }

    private static int occurrences(String s, String part) { return (s.length() - s.replace(part, "").length()) / part.length(); }
}
