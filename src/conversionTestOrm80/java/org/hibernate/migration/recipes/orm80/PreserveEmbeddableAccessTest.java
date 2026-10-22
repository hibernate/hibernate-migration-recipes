package org.hibernate.migration.recipes.orm80;

import java.util.*;
import org.hibernate.migration.testing.ApiValidation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.SourceFile;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.xml.XmlParser;
import org.hibernate.migration.recipes.table.SkippedMigrations;

import static org.junit.jupiter.api.Assertions.*;

/// Exercises access preservation, shared-type safety, nested plans, and descriptor blockers.
/// Every ordinary fixture is compiled against source and target APIs and rerun for idempotence.
/// @author Steve Ebersole
class PreserveEmbeddableAccessTest {
    private static final String HEADER = "package fixture; import jakarta.persistence.*; import java.util.*;\n";
    private static final String DETAIL = "\n@Embeddable class Detail { String text; public String getText() { return text; } public void setText(String x) { text=x; } }\n";
    private static final String FIELD = "@jakarta.persistence.Access(jakarta.persistence.AccessType.FIELD)";
    private static final String PROPERTY = "@jakarta.persistence.Access(jakarta.persistence.AccessType.PROPERTY)";

    @ParameterizedTest
    @ValueSource(strings = {"org.hibernate.migration.recipes.orm80.PreserveEmbeddableAccess", "org.hibernate.migration.recipes.orm80-required", "org.hibernate.migration.recipes.orm80"})
    void identifierOverrideAndAggregates(String recipe) {
        var result = ApiValidation.run(ApiValidation.composite(recipe), HEADER + """
                @Entity @Access(AccessType.PROPERTY) class Owner {
                    @Id @Access(AccessType.FIELD) long id;
                    List<Detail> details;
                    @ElementCollection public List<Detail> getDetails() { return details; }
                    public void setDetails(List<Detail> x) { details=x; }
                }
                """ + DETAIL, "orm74");
        assertTrue(result.text().contains(FIELD), result.text());
        assertTrue(result.skipped().isEmpty(), result.skipped().stream().map(r -> r.getReasonCode() + ":" + r.getSubject() + ":" + r.getMessage()).toList().toString());
    }

    @Test void reverseIdentifierAndCollectionOverridePrecedence() {
        for (String override : List.of("", "@Access(AccessType.FIELD)")) {
            var result = run("""
                    @Entity @Access(AccessType.FIELD) class Owner {
                        long id;
                        @Id @Access(AccessType.PROPERTY) public long getId() { return id; }
                        public void setId(long x) { id=x; }
                        @ElementCollection %s List<Detail> details;
                    }
                    """.formatted(override) + DETAIL);
            assertTrue(result.skipped().isEmpty(), result.skipped().stream().map(r -> r.getReasonCode() + ":" + r.getSubject() + ":" + r.getMessage()).toList().toString());
            assertEquals(override.isEmpty(), result.text().contains(PROPERTY), result.text());
        }
    }

    @Test void singularCollectionAndMapKeyOverrides() {
        for (String declaration : List.of("@Embedded @Access(AccessType.PROPERTY) Detail getDetails() { return null; }",
                "public @Embedded @Access(AccessType.PROPERTY) Detail getDetails() { return null; }",
                "@ElementCollection @Access(AccessType.PROPERTY) List<Detail> getDetails() { return null; }",
                "@ElementCollection @Access(AccessType.PROPERTY) Map<Detail,String> getDetails() { return null; }",
                "@OneToMany @Access(AccessType.PROPERTY) Map<Detail,Target> getDetails() { return null; }")) {
            var result = run("@Entity @Access(AccessType.FIELD) class Owner { @Id long id; " + declaration + " }\n"
                    + "@Entity class Target { @Id long id; }" + DETAIL);
            assertTrue(result.text().contains(PROPERTY), result.text());
            assertTrue(result.skipped().isEmpty(), result.skipped().stream().map(r -> r.getReasonCode() + ":" + r.getSubject() + ":" + r.getMessage()).toList().toString());
        }
    }

    @Test void embeddedIdentifierOverride() {
        var result = run("""
                @Entity @Access(AccessType.FIELD) class Owner {
                    @EmbeddedId @Access(AccessType.PROPERTY) public Detail getId() { return null; }
                    public void setId(Detail x) {}
                }
                """ + DETAIL);
        assertTrue(result.text().contains(PROPERTY), result.text());
    }

    @Test void unchangedAndExplicitMappings() {
        for (String detail : List.of(DETAIL, DETAIL.replace("@Embeddable", "@Embeddable @Access(AccessType.PROPERTY)"))) {
            String source = HEADER + "@Entity class Owner { @Id long id; @Embedded Detail detail; }" + detail;
            var result = ApiValidation.run(new PreserveEmbeddableAccess(), source, "orm74");
            assertEquals(source, result.text());
            assertTrue(result.skipped().isEmpty(), result.skipped().stream().map(r -> r.getReasonCode() + ":" + r.getSubject() + ":" + r.getMessage()).toList().toString());
        }
    }

    @Test void conflictsIncludeUnchangedUses() {
        var result = run("""
                @Entity class Owner { @Id long id; @Embedded @Access(AccessType.PROPERTY) public Detail getDetail() {return null;} }
                @Entity class Other { @Id long id; @Embedded Detail detail; }
                """ + DETAIL);
        assertFalse(result.text().contains(PROPERTY), result.text());
        assertEquals(2, result.skipped().size());
        assertTrue(result.skipped().stream().allMatch(r -> r.getReasonCode().equals("EMBEDDABLE_ACCESS_CONFLICT")));
    }

    @Test void nestedPreservationAvoidsRedundantAnnotation() {
        var result = run("""
                @Entity class Owner { @Id long id; @Embedded @Access(AccessType.PROPERTY) public Detail getDetail() { return null; } }
                @Embeddable class Detail {
                    Nested nested;
                    @Embedded @Access(AccessType.PROPERTY) public Nested getNested() { return nested; }
                    public void setNested(Nested x) { nested=x; }
                }
                @Embeddable class Nested { String text; public String getText() {return text;} public void setText(String x) {text=x;} }
                """);
        assertTrue(result.skipped().isEmpty(), result.skipped().stream().map(r -> r.getReasonCode() + ":" + r.getSubject() + ":" + r.getMessage()).toList().toString());
        assertEquals(1, occurrences(result.text(), PROPERTY), result.text());
    }

    @Test void nestedConflictRejectsParent() {
        var result = run("""
                @Entity class Owner { @Id long id; @Embedded @Access(AccessType.PROPERTY) public Detail getDetail() {return null;} }
                @Embeddable class Detail { @Embedded @Access(AccessType.FIELD) Nested nested; }
                @Entity class Other { @Id long id; @Embedded @Access(AccessType.PROPERTY) public Nested getNested() {return null;} }
                @Embeddable class Nested { String text; public String getText(){return text;} public void setText(String x){text=x;} }
                """);
        assertFalse(result.text().contains(PROPERTY), result.text());
        assertTrue(result.skipped().stream().anyMatch(r -> r.getReasonCode().equals("EMBEDDABLE_ACCESS_DEPENDENCY")), result.skipped().stream().map(r -> r.getReasonCode() + ":" + r.getSubject() + ":" + r.getMessage()).toList().toString());
    }

    @Test void declaringSuperclassAndInheritedIdentifier() {
        var result = run("""
                @MappedSuperclass @Access(AccessType.PROPERTY) class Base {
                    @Id @Access(AccessType.FIELD) long id;
                    @ElementCollection public List<Detail> getDetails(){return null;}
                }
                @Entity @Access(AccessType.FIELD) class Owner extends Base {}
                """ + DETAIL);
        assertTrue(result.text().contains(FIELD), result.text());
        assertTrue(result.skipped().isEmpty(), result.skipped().stream().map(r -> r.getReasonCode() + ":" + r.getSubject() + ":" + r.getMessage()).toList().toString());
    }

    @Test void rawCollectionAndCustomInstantiatorAreReported() {
        var raw = run("@Entity class Owner { @Id long id; @ElementCollection List details; }");
        assertEquals("EMBEDDABLE_ACCESS_UNRESOLVED", raw.skipped().getFirst().getReasonCode());
        var custom = run("@Entity class Owner { @Id long id; @Embedded @Access(AccessType.PROPERTY) public Detail getDetail(){return null;} }"
                + DETAIL.replace("@Embeddable", "@Embeddable @org.hibernate.annotations.EmbeddableInstantiator(Maker.class)")
                + "class Maker implements org.hibernate.metamodel.spi.EmbeddableInstantiator { public Object instantiate(org.hibernate.metamodel.spi.ValueAccess v) {return null;} public boolean isInstance(Object o){return false;} public boolean isSameClass(Object o){return false;} }");
        assertEquals("EMBEDDABLE_ACCESS_UNSUPPORTED", custom.skipped().getFirst().getReasonCode());
    }

    @Test void xmlOverridesAndMissingMappingFilesBlockEdits() {
        String source = HEADER + "@Entity class Owner { @Id long id; @Embedded @Access(AccessType.PROPERTY) public Detail getDetail(){return null;} }" + DETAIL;
        for (String descriptor : List.of("<entity-mappings><package>fixture</package><embeddable class=\"Detail\"/></entity-mappings>",
                "<persistence><persistence-unit name=\"test\"><mapping-file>missing.xml</mapping-file></persistence-unit></persistence>",
                "<entity-mappings><access>FIELD</access></entity-mappings>",
                "<entity-mappings><persistence-unit-metadata><xml-mapping-metadata-complete/></persistence-unit-metadata></entity-mappings>")) {
            var ctx = new InMemoryExecutionContext(t -> { throw new AssertionError(t); });
            List<SourceFile> files = new ArrayList<>(ApiValidation.parser("orm74").build().parse(ctx, source).toList());
            files.addAll(new XmlParser().parse(ctx, descriptor).toList());
            var result = new PreserveEmbeddableAccess().run(new InMemoryLargeSourceSet(files), ctx);
            assertEquals(0, result.getChangeset().size());
            assertTrue(result.getDataTableRows(SkippedMigrations.class).stream().allMatch(r -> r.getReasonCode().equals("EMBEDDABLE_ACCESS_XML_DEPENDENT")));
            assertFalse(result.getDataTableRows(SkippedMigrations.class).isEmpty());
        }
    }

    @Test void inputOrderDoesNotChangePlans() {
        Map<String,String> sources = new LinkedHashMap<>();
        sources.put("fixture/Owner.java", HEADER + "@Entity class Owner { @Id long id; @Embedded @Access(AccessType.PROPERTY) public Detail getDetail(){return null;} }");
        sources.put("fixture/Detail.java", HEADER + DETAIL);
        var first = ApiValidation.run(new PreserveEmbeddableAccess(), sources, "orm74");
        Map<String,String> reversed = new LinkedHashMap<>();
        reversed.put("fixture/Detail.java", sources.get("fixture/Detail.java")); reversed.put("fixture/Owner.java", sources.get("fixture/Owner.java"));
        var second = ApiValidation.run(new PreserveEmbeddableAccess(), reversed, "orm74");
        assertEquals(first.files(), second.files());
    }

    @Test void parentAnnotationCanRequirePreviouslyUnchangedChildAnnotation() {
        var result = run("""
                @Entity class Owner { @Id long id; @Embedded @Access(AccessType.PROPERTY) public Detail getDetail(){return null;} }
                @Embeddable class Detail {
                    Nested nested;
                    @Embedded public Nested getNested(){return nested;}
                    public void setNested(Nested x){nested=x;}
                }
                @Embeddable class Nested { String text; public String getText(){return text;} public void setText(String x){text=x;} }
                """);
        assertTrue(result.skipped().isEmpty());
        assertTrue(result.text().contains(PROPERTY), result.text());
        assertTrue(result.text().contains(FIELD), result.text());
    }

    @Test void homogeneousIdentifierMapperAndMixedMapper() {
        for (boolean mixed : List.of(false,true)) {
            var result = run("""
                    @Entity @IdClass(Keys.class) @Access(AccessType.FIELD) class Owner {
                        @Id @Access(AccessType.PROPERTY) public long getA(){return 1;}
                        public void setA(long x){}
                        %s
                        @ElementCollection List<Detail> details;
                    }
                    class Keys implements java.io.Serializable { public long a; public long b; }
                    """.formatted(mixed ? "@Id long b;" : "@Id @Access(AccessType.PROPERTY) public long getB(){return 2;} public void setB(long x){}") + DETAIL);
            if (mixed) {
                assertFalse(result.text().contains(PROPERTY));
                assertTrue(result.skipped().stream().anyMatch(r -> r.getReasonCode().equals("EMBEDDABLE_ACCESS_UNRESOLVED")));
            }
            else { assertTrue(result.text().contains(PROPERTY), result.text()); assertTrue(result.skipped().isEmpty()); }
        }
    }

    @Test void nestedMapKeyAndRecursiveEmbeddingAreConservative() {
        var nested = run("""
                @Entity class Owner { @Id long id; @ElementCollection @Access(AccessType.PROPERTY) public Map<Detail,String> getDetails(){return null;} }
                @Embeddable class Detail { @Embedded Nested nested; @Embedded public Nested getNested(){return nested;} public void setNested(Nested x){nested=x;} }
                @Embeddable class Nested { String text; }
                """);
        assertFalse(nested.text().contains(PROPERTY));
        assertFalse(nested.skipped().isEmpty());
        var cycle = run("@Entity class Owner { @Id long id; @Embedded @Access(AccessType.PROPERTY) public Detail getDetail(){return null;} }"
                + "@Embeddable class Detail { @Embedded @Access(AccessType.FIELD) Detail nested; }");
        assertFalse(cycle.text().contains(PROPERTY));
        assertFalse(cycle.skipped().isEmpty());
    }

    @Test void explicitTargetTypesAndNamedMapKeys() {
        var result = run("""
                @Entity class Owner { @Id long id;
                    @ElementCollection(targetClass=Detail.class) @Access(AccessType.PROPERTY) public List getDetails(){return null;}
                    @ElementCollection @MapKey(name="text") Map<String,Detail> named;
                }
                """ + DETAIL);
        assertFalse(result.text().contains(PROPERTY));
        assertTrue(result.skipped().stream().anyMatch(r -> r.getReasonCode().equals("EMBEDDABLE_ACCESS_CONFLICT")));
        var explicit = run("""
                @Entity class Owner { @Id long id;
                    @ElementCollection(targetClass=Detail.class) @Access(AccessType.PROPERTY) public List getDetails(){return null;}
                }
                """ + DETAIL);
        assertTrue(explicit.text().contains(PROPERTY));
        assertTrue(explicit.skipped().isEmpty());
    }

    @Test void qualifiedAnnotationsCommentsAndNameCollisions() {
        var result = run("""
                @Entity class Owner { @Id long id;
                    @jakarta.persistence.Embedded @jakarta.persistence.Access(jakarta.persistence.AccessType.PROPERTY)
                    public Detail getDetail(){return null;}
                }
                @interface Access {}
                class AccessType {}
                // retained class comment
                @Embeddable class Detail { String text; public String getText(){return text;} public void setText(String x){text=x;} }
                """);
        assertTrue(result.text().contains(PROPERTY),result.text());
        assertEquals(1,occurrences(result.text(),"// retained class comment"));
        assertTrue(result.skipped().isEmpty());
    }

    @Test void missingSuperclassAndMissingEmbeddableSourcesBlockGlobalEdit() {
        var complete = Map.of(
                "fixture/Base.java", HEADER + "@MappedSuperclass class Base { @Id long id; }",
                "fixture/Owner.java", HEADER + "@Entity class Owner extends Base { @Embedded @Access(AccessType.PROPERTY) public Detail getDetail(){return null;} }",
                "fixture/Detail.java", HEADER + DETAIL);
        // A supplied source model with an unavailable mapped superclass is still attributed
        // by parser dependencies, so this exercises completeness rather than malformed Java.
        var ctx = new InMemoryExecutionContext(t -> { throw new AssertionError(t); });
        var parser = ApiValidation.parser("orm74").dependsOn(complete.get("fixture/Base.java")).build();
        var inputs = parser.parse(ctx, complete.get("fixture/Owner.java"), complete.get("fixture/Detail.java")).toList();
        var result = new PreserveEmbeddableAccess().run(new InMemoryLargeSourceSet(inputs),ctx);
        assertEquals(0,result.getChangeset().size());
        assertEquals("EMBEDDABLE_ACCESS_UNRESOLVED", result.getDataTableRows(SkippedMigrations.class).getFirst().getReasonCode());
        var absent = ApiValidation.parser("orm74").dependsOn(HEADER + DETAIL).build()
                .parse(ctx, HEADER + "@Entity class Owner { @Id long id; @Embedded @Access(AccessType.PROPERTY) public Detail getDetail(){return null;} }").toList();
        var missing = new PreserveEmbeddableAccess().run(new InMemoryLargeSourceSet(absent),ctx);
        assertEquals(0,missing.getChangeset().size());
        assertFalse(missing.getDataTableRows(SkippedMigrations.class).isEmpty());
    }

    @Test void diagnosticsUseEmbeddingCoordinatesAndUnrelatedXmlDoesNotBlock() {
        String source = HEADER + """
                @Entity class Owner {
                    @Id long id;
                    @Embedded @Access(AccessType.PROPERTY)
                    public Detail getDetail(){return null;}
                }
                @Entity class Other { @Id long id; @Embedded Detail detail; }
                """ + DETAIL;
        var result = ApiValidation.run(new PreserveEmbeddableAccess(),source,"orm74");
        var row = result.skipped().stream().filter(r -> r.getSubject().contains("Owner.detail")).findFirst().orElseThrow();
        assertEquals(4,row.getLine());
        assertEquals(5,row.getColumn());
        assertEquals("Example.java",row.getSourcePath());
        String safe = HEADER + "@Entity class Owner { @Id long id; @Embedded @Access(AccessType.PROPERTY) public Detail getDetail(){return null;} }" + DETAIL;
        var ctx = new InMemoryExecutionContext(t -> {throw new AssertionError(t);});
        List<SourceFile> inputs = new ArrayList<>(ApiValidation.parser("orm74").build().parse(ctx,safe).toList());
        inputs.addAll(new XmlParser().parse(ctx,"<orm:entity-mappings xmlns:orm=\"https://jakarta.ee/xml/ns/persistence/orm\"><orm:embeddable class=\"unrelated.Type\"/></orm:entity-mappings>").toList());
        var migrated = new PreserveEmbeddableAccess().run(new InMemoryLargeSourceSet(inputs),ctx);
        assertEquals(1,migrated.getChangeset().size());
        assertTrue(migrated.getDataTableRows(SkippedMigrations.class).isEmpty());
    }

    @Test void unsupportedSharedUseBlocksOtherwiseResolvablePlan() {
        var result = run("""
                @Entity class Owner { @Id long id; @Embedded @Access(AccessType.PROPERTY) public Detail getDetail(){return null;} }
                @Entity class Other { @Id long id; @Embedded @MapsId Detail detail; }
                """ + DETAIL);
        assertFalse(result.text().contains(PROPERTY));
        assertTrue(result.skipped().stream().anyMatch(r -> r.getReasonCode().equals("EMBEDDABLE_ACCESS_UNSUPPORTED")));
    }

    @Test void recordAndEmbeddableInheritanceRequireReview() {
        for (String declaration : List.of("@Embeddable record Detail(String text) {}",
                "class Parent { String inherited; } @Embeddable class Detail extends Parent { String text; }")) {
            var result = run("@Entity class Owner { @Id long id; @Embedded @Access(AccessType.PROPERTY) public Detail getDetail(){return null;} }" + declaration);
            assertFalse(result.text().contains(PROPERTY));
            assertTrue(result.skipped().stream().anyMatch(r -> r.getReasonCode().equals("EMBEDDABLE_ACCESS_UNSUPPORTED")));
        }
    }

    @Test void explicitIdentifierPlacementDoesNotGuessUnannotatedSuperclassAccess() {
        var result = run("""
                @MappedSuperclass class Base { @Embedded Detail detail; }
                @Entity @Access(AccessType.PROPERTY) class Owner extends Base { @Id @Access(AccessType.FIELD) long id; }
                """ + DETAIL);
        assertFalse(result.text().contains(PROPERTY));
        assertFalse(result.text().contains(FIELD));
        assertTrue(result.skipped().stream().anyMatch(r -> r.getReasonCode().equals("EMBEDDABLE_ACCESS_UNRESOLVED")));
    }

    @Test void customScalarAccessorBlocksEmbeddablePlan() {
        var result = run("@Entity class Owner { @Id long id; @Embedded @Access(AccessType.PROPERTY) public Detail getDetail(){return null;} }"
                + DETAIL.replace("String text;", "@org.hibernate.annotations.AttributeAccessor String text;"));
        assertFalse(result.text().contains(PROPERTY));
        assertTrue(result.skipped().stream().anyMatch(r -> r.getReasonCode().equals("EMBEDDABLE_ACCESS_UNSUPPORTED")));
    }

    @Test void nonMappedSuperclassMembersAreNotPersistentUses() {
        var result = run("""
                class Base { @Embedded @Access(AccessType.PROPERTY) public Detail getDetail(){return null;} }
                @Entity class Owner extends Base { @Id long id; }
                """ + DETAIL);
        assertFalse(result.text().contains(PROPERTY));
        assertTrue(result.skipped().isEmpty());
    }

    @Test void duplicateInheritedPropertySelectionRequiresReview() {
        var result = run("""
                @MappedSuperclass class Base { @Embedded Detail detail; }
                @Entity class Owner extends Base { @Id long id; @Embedded @Access(AccessType.PROPERTY) public Detail getDetail(){return null;} }
                """ + DETAIL);
        assertFalse(result.text().contains(PROPERTY));
        assertFalse(result.skipped().isEmpty());
    }

    @Test void collectionsInsideExplicitAndMigratedContainersKeepIdentifierFallback() {
        for (boolean explicit : List.of(false,true)) {
            var result = run("""
                    @Entity class Owner { @Id long id; @Embedded @Access(AccessType.PROPERTY) public Container getContainer(){return null;} }
                    @Embeddable %s class Container {
                        List<Detail> details;
                        @ElementCollection public List<Detail> getDetails(){return details;}
                        public void setDetails(List<Detail> x){details=x;}
                    }
                    """.formatted(explicit ? "@Access(AccessType.PROPERTY)" : "") + DETAIL);
            assertTrue(result.skipped().isEmpty());
            assertTrue(result.text().contains(FIELD),result.text());
            assertEquals(explicit ? 0 : 1,occurrences(result.text(),PROPERTY),result.text());
        }
    }

    @Test void reverseEmbeddingOverridesAndImplicitEmbedding() {
        for (String member : List.of("@Embedded @Access(AccessType.FIELD) Detail detail;",
                "@Access(AccessType.FIELD) Detail detail;",
                "@ElementCollection @Access(AccessType.FIELD) List<Detail> details;",
                "@ElementCollection @Access(AccessType.FIELD) Map<Detail,String> details;")) {
            var result = run("@Entity @Access(AccessType.PROPERTY) class Owner { @Id public long getId(){return 1;} public void setId(long x){} " + member + " }" + DETAIL);
            assertTrue(result.text().contains(FIELD),result.text());
            assertTrue(result.skipped().isEmpty());
        }
    }

    @Test void explicitParentStillAnalyzesNestedMemberOverride() {
        var result = run("""
                @Entity class Owner { @Id long id; @Embedded Container container; }
                @Embeddable @Access(AccessType.PROPERTY) class Container { @Embedded @Access(AccessType.FIELD) Detail detail; }
                """ + DETAIL);
        assertTrue(result.text().contains(FIELD),result.text());
        assertEquals(1,occurrences(result.text(),FIELD));
        assertTrue(result.skipped().isEmpty());
    }

    @Test void unrelatedLookalikeAnnotationsAreIgnored() {
        String source = HEADER + "@Entity class Owner { @Id long id; @Embedded Detail detail; }"
                + DETAIL.replace("@Embeddable", "@Embeddable @Access") + "@interface Access {}";
        var result = ApiValidation.run(new PreserveEmbeddableAccess(),source,"orm74");
        assertEquals(source,result.text());
        assertTrue(result.skipped().isEmpty());
    }

    private static ApiValidation.Outcome run(String body) { return ApiValidation.run(new PreserveEmbeddableAccess(), HEADER + body, "orm74"); }
    private static int occurrences(String text, String value) { return (text.length() - text.replace(value, "").length()) / value.length(); }
}
