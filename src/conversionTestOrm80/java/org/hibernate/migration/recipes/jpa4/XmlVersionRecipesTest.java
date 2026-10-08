package org.hibernate.migration.recipes.jpa4;

import org.hibernate.migration.recipes.orm80.MigrateMappingXml;
import org.hibernate.migration.recipes.xml.Descriptor;
import org.junit.jupiter.api.Test;
import org.openrewrite.Recipe;
import java.util.*;
import static org.hibernate.migration.recipes.jpa4.XmlRecipeSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/// Exercises both new version recipes against their real schemas and namespace boundaries.
/// @author Steve Ebersole
class XmlVersionRecipesTest {
    @Test void schemaUpdatesPreserveFormattingAndCompose() throws Exception {
        for (Descriptor d : List.of(Descriptor.ORM, Descriptor.MAPPING)) {
            for (String version : d.versions) {
                List<Recipe> recipes = new ArrayList<>(List.of(leaf(d), composite("orm80")));
                for (Recipe recipe : recipes) {
                    for (String scheme : d == Descriptor.MAPPING ? List.of("https:", "http:") : List.of("https:")) {
                        String schema = d.schema(version).replace("https:", scheme);
                        String input = "<?xml version=\"1.0\"?>\r\n<!-- keep -->\r\n<p:entity-mappings xmlns:p=\"" + d.namespace + "\" xmlns:s=\"http://www.w3.org/2001/XMLSchema-instance\" version='" + version + "' s:schemaLocation=\"urn:other other.xsd\n  " + d.namespace + "\t" + schema + "  \"/>";
                        validate(input, resource(d, version));
                        var result = run(recipe, input);
                        assertEquals(input.replace("version='" + version + "'", "version='" + d.target + "'").replace(schema, d.schema(d.target)), result.text());
                        assertTrue(result.rows().isEmpty());
                        validate(result.text(), resource(d, d.target));
                    }
                }
            }
        }
    }

    @Test void absentAndUnrelatedSchemaPairsArePreserved() throws Exception {
        for (Descriptor d : List.of(Descriptor.ORM, Descriptor.MAPPING)) {
            String version = d == Descriptor.ORM ? "3.2" : "7.0";
            for (String extra : List.of("", "s:schemaLocation=\"urn:other other.xsd\"", "xmlns:o=\"urn:other\" o:schemaLocation=\"custom\"")) {
                String input = root(d, version, extra);
                var result = run(leaf(d), input);
                assertEquals(input.replace("version=\"" + version + "\"", "version=\"" + d.target + "\""), result.text());
                assertTrue(result.rows().isEmpty());
            }
        }
    }

    @Test void conflictingMetadataIsReportedWithoutEdits() {
        for (Descriptor d : List.of(Descriptor.ORM, Descriptor.MAPPING)) {
            String version = d == Descriptor.ORM ? "3.2" : "7.0";
            Map<String, String> cases = new LinkedHashMap<>();
            cases.put(root(d, "", ""), "XML_VERSION_UNSUPPORTED");
            cases.put(root(d, "99.0", ""), "XML_VERSION_UNSUPPORTED");
            cases.put(root(d, version, "s:schemaLocation=\"" + d.namespace + "\""), "XML_SCHEMA_CONFLICT");
            cases.put(root(d, version, "s:schemaLocation=\"" + d.namespace + " " + d.schema(version) + " " + d.namespace + " " + d.schema(version) + "\""), "XML_SCHEMA_CONFLICT");
            cases.put(root(d, version, "s:schemaLocation=\"" + d.namespace + " custom.xsd\""), "XML_SCHEMA_LOCATION_UNSUPPORTED");
            cases.put(root(d, d.target, "s:schemaLocation=\"" + d.namespace + " " + d.schema(version) + "\""), "XML_SCHEMA_LOCATION_UNSUPPORTED");
            cases.put(root(d, version, "s:noNamespaceSchemaLocation=\"custom.xsd\""), "XML_SCHEMA_CONFLICT");
            cases.put(root(d, version, "unbound:schemaLocation=\"custom.xsd\""), "XML_SCHEMA_CONFLICT");
            cases.put(root(d, version, "schemaLocation=\"custom.xsd\""), "XML_SCHEMA_CONFLICT");
            cases.put(root(d, version, "xmlns:t=\"http://www.w3.org/2001/XMLSchema-instance\" s:schemaLocation=\"urn:other other.xsd\" t:schemaLocation=\"urn:other other.xsd\""), "XML_SCHEMA_CONFLICT");
            for (var entry : cases.entrySet()) {
                String input = "<!-- original -->\n" + entry.getKey();
                var result = run(leaf(d), input);
                assertEquals(input, result.text());
                assertEquals(1, result.rows().size());
                assertEquals(entry.getValue(), result.rows().getFirst().getReasonCode());
                assertEquals(2, result.rows().getFirst().getLine());
                assertEquals(1, result.rows().getFirst().getColumn());
                assertEquals("config/custom.xml", result.rows().getFirst().getSourcePath());
            }
        }
    }

    @Test void familiesAndLegacySchemasAreIsolated() {
        for (Descriptor d : List.of(Descriptor.ORM, Descriptor.MAPPING)) {
            for (String input : List.of("<persistence xmlns=\"https://jakarta.ee/xml/ns/persistence\" version=\"3.2\"/>",
                    "<entity-mappings xmlns=\"http://xmlns.jcp.org/xml/ns/persistence/orm\" version=\"2.2\"/>",
                    "<hibernate-mapping/>", "<entity-mappings xmlns=\"urn:other\" version=\"3.2\"/>",
                    root(d == Descriptor.ORM ? Descriptor.MAPPING : Descriptor.ORM, d == Descriptor.ORM ? "7.0" : "3.2", ""),
                    root(d, d.target, ""), "<wrapper>" + root(d, "3.2", "") + "</wrapper>")) {
                var result = run(leaf(d), input);
                assertEquals(input, result.text());
                assertTrue(result.rows().isEmpty());
            }
        }
    }

    private static Recipe leaf(Descriptor d) { return d == Descriptor.ORM ? new MigrateOrmXml() : new MigrateMappingXml(); }
    static String resource(Descriptor d, String v) { return d == Descriptor.ORM ? "org/hibernate/jpa/orm_" + v.replace('.', '_') + ".xsd" : "org/hibernate/xsd/mapping/mapping-" + v + ".xsd"; }
    private static String root(Descriptor d, String v, String extra) {
        return "<entity-mappings xmlns=\"" + d.namespace + "\" xmlns:s=\"http://www.w3.org/2001/XMLSchema-instance\" " + (v.isEmpty() ? "" : "version=\"" + v + "\" ") + extra + "/>";
    }
}
