package org.hibernate.migration.recipes.orm80;

import org.hibernate.migration.recipes.jpa4.XmlRecipeSupport;
import org.hibernate.migration.recipes.table.SkippedMigrations;
import org.hibernate.migration.testing.ApiValidation;
import org.hibernate.migration.testing.RecipeExecutionContexts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openrewrite.Recipe;
import org.openrewrite.Result;
import org.openrewrite.internal.InMemoryLargeSourceSet;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies composite selection and staged migration of descriptors and build configuration.
///
/// @author Steve Ebersole
class CompositeResourceSelectionTest {
    private static Recipe recipe(String selection) {
        return ApiValidation.composite("org.hibernate.migration.recipes." + selection);
    }

    private static Map<String, String> run(String selection, Map<String, String> files) {
        var context = RecipeExecutionContexts.standard(error -> { throw new AssertionError(error); });
        var sources = files.entrySet().stream()
                .map(entry -> OrmCoordinatesTest.parse(entry.getKey(), entry.getValue(), context)).toList();
        var result = recipe(selection).run(new InMemoryLargeSourceSet(sources), context);
        Map<String, String> output = new LinkedHashMap<>(files);
        for (Result change : result.getChangeset().getAllResults()) {
            assertNotNull(change.getAfter());
            output.put(change.getAfter().getSourcePath().toString(), change.getAfter().printAll());
        }
        assertTrue(result.getDataTableRows(SkippedMigrations.class).isEmpty(),
                () -> result.getDataTableRows(SkippedMigrations.class).stream()
                        .map(row -> row.getRecipe() + ": " + row.getReasonCode() + ": " + row.getMessage()).toList().toString());
        var secondContext = RecipeExecutionContexts.standard(error -> { throw new AssertionError(error); });
        var reparsed = output.entrySet().stream()
                .map(entry -> OrmCoordinatesTest.parse(entry.getKey(), entry.getValue(), secondContext)).toList();
        var second = recipe(selection).run(new InMemoryLargeSourceSet(reparsed), secondContext);
        assertTrue(second.getChangeset().getAllResults().isEmpty(), "Fresh parser second run changed resources");
        assertTrue(second.getDataTableRows(SkippedMigrations.class).isEmpty());
        return output;
    }

    @Test
    void descriptorsSelectRequiredContentAndOptionalJpaMappingMetadata() throws Exception {
        Map<String, String> input = Map.of(
                "META-INF/persistence.xml", """
                        <persistence xmlns="https://jakarta.ee/xml/ns/persistence" version="3.2">
                            <persistence-unit name="example">
                                <class>example.package-info</class>
                                <class>example.Item</class>
                            </persistence-unit>
                        </persistence>
                        """,
                "mapping.xml", """
                        <entity-mappings xmlns="http://www.hibernate.org/xsd/orm/mapping" version="7.0">
                            <entity class="example.Item">
                                <table name="items" comment="Items"/>
                                <attributes>
                                    <basic name="label"><column name="label" comment="Label"/></basic>
                                    <many-to-many name="items" orphan-removal="true"/>
                                </attributes>
                            </entity>
                        </entity-mappings>
                        """,
                "META-INF/orm.xml", """
                        <entity-mappings xmlns="https://jakarta.ee/xml/ns/persistence/orm" version="3.2"/>
                        """);
        var required = run("orm80-required", input);
        assertTrue(required.get("META-INF/persistence.xml").contains("<package-descriptor>example</package-descriptor>"));
        assertTrue(required.get("mapping.xml").contains("<comment>Items</comment>"));
        assertTrue(required.get("mapping.xml").contains("<comment>Label</comment>"));
        assertFalse(required.get("mapping.xml").contains("orphan-removal"));
        assertEquals(input.get("META-INF/orm.xml"), required.get("META-INF/orm.xml"));
        var full = run("orm80", input);
        assertTrue(full.get("META-INF/orm.xml").contains("version=\"4.0\""));
        assertEquals(full, run("orm80", required));
        for (var output : List.of(required, full)) {
            XmlRecipeSupport.validate(output.get("META-INF/persistence.xml"), "org/hibernate/jpa/persistence_4_0.xsd");
            XmlRecipeSupport.validate(output.get("mapping.xml"), "org/hibernate/xsd/mapping/mapping-8.0.xsd");
        }
        XmlRecipeSupport.validate(full.get("META-INF/orm.xml"), "org/hibernate/jpa/orm_4_0.xsd");
    }

    @ParameterizedTest
    @ValueSource(strings = {"build.gradle", "build.gradle.kts", "pom.xml", "build.xml"})
    void buildMigrationPreservesLegacyEnhancementUntilComprehensiveSelection(String path) {
        String source = ApiValidation.environments().value("orm74.ormVersion");
        String target = ApiValidation.environments().value("orm80.ormVersion");
        String input;
        if (path.endsWith(".gradle") || path.endsWith(".kts")) {
            input = "plugins { id(\"org.hibernate.orm\") version \"" + source + "\" }\n"
                    + "dependencies { implementation(\"org.hibernate:hibernate-core:" + source + "\") }\n"
                    + "hibernate { enhancement { enableExtendedEnhancement = true } }\n";
        }
        else if (path.equals("pom.xml")) {
            input = "<project><modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>example</artifactId><version>1</version>"
                    + "<dependencies><dependency><groupId>org.hibernate</groupId><artifactId>hibernate-core</artifactId><version>" + source
                    + "</version></dependency></dependencies><build><plugins><plugin><groupId>org.hibernate.orm</groupId>"
                    + "<artifactId>hibernate-maven-plugin</artifactId><version>" + source
                    + "</version><configuration><enableExtendedEnhancement>true</enableExtendedEnhancement></configuration>"
                    + "</plugin></plugins></build></project>";
        }
        else {
            input = "<project xmlns:m='antlib:org.apache.maven.artifact.ant'>"
                    + "<taskdef name='enhance' classname='org.hibernate.tool.enhance.EnhancementTask'/>"
                    + "<target name='example'><m:dependencies><dependency groupId='org.hibernate' artifactId='hibernate-core' version='" + source
                    + "'/></m:dependencies><enhance enableExtendedEnhancement='true'/></target></project>";
        }
        Map<String, String> files = Map.of(path, input);
        var required = run("orm80-required", files);
        assertTrue(required.get(path).contains("enableExtendedEnhancement"));
        assertTrue(required.get(path).contains(target));
        assertFalse(required.get(path).contains(source));
        assertTrue(required.get(path).contains(path.endsWith(".gradle") || path.endsWith(".kts")
                ? "org.hibernate.orm:hibernate-core" : "org.hibernate.orm"));
        var full = run("orm80", files);
        assertFalse(full.get(path).contains("enableExtendedEnhancement"));
        assertTrue(full.get(path).contains("enableClientEnhancement"));
        assertEquals(full, run("orm80", required));
        for (var output : List.of(required, full)) assertFalse(output.get(path).contains("hibernate-platform"));
    }
}
