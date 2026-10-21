package org.hibernate.migration.recipes.orm80;

import org.junit.jupiter.api.Test;
import org.openrewrite.Recipe;
import java.util.List;
import static org.hibernate.migration.recipes.jpa4.XmlRecipeSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/// Verifies namespace conversion against original bindings, schema pairs, and atomic rollback.
///
/// @author Steve Ebersole
class MappingXmlNamespaceTest {
    private static final String HTTP = "http://www.hibernate.org/xsd/orm/mapping";
    private static final String HTTPS = "https://www.hibernate.org/xsd/orm/mapping";

    @Test void namespaceVersionAndSchemaMatrix() throws Exception {
        for (String namespace : List.of(HTTP, HTTPS)) {
            for (String version : List.of("7.0", "8.0")) {
                for (String schemaNamespace : List.of(HTTP, HTTPS)) {
                    for (String scheme : List.of("http:", "https:")) {
                        String location = scheme + "//www.hibernate.org/xsd/orm/mapping/mapping-" + version + ".xsd";
                        String input = "<?xml version='1.0'?>\r\n<!-- keep -->\r\n"
                                + "<m:entity-mappings xmlns:m='" + namespace + "' version='" + version
                                + "' xmlns:s='http://www.w3.org/2001/XMLSchema-instance' s:schemaLocation='urn:other other.xsd\n  "
                                + schemaNamespace + "\t" + location + "  '/>";
                        String expected = input.replace("xmlns:m='" + namespace, "xmlns:m='" + HTTPS)
                                .replace("version='" + version, "version='8.0")
                                .replace(schemaNamespace + "\t" + location, HTTPS + "\t" + HTTPS + "/mapping-8.0.xsd");
                        for (Recipe recipe : List.of(new MigrateMappingXml(), composite("orm80"))) {
                            var result = run(recipe, input);
                            assertEquals(expected, result.text());
                            assertTrue(result.rows().isEmpty());
                            validate(result.text(), "org/hibernate/xsd/mapping/mapping-8.0.xsd");
                        }
                    }
                }
            }
        }
    }

    @Test void nestedBindingsAndMixedContentUseOriginalScopes() {
        String input = """
                <entity-mappings xmlns='%s' xmlns:m='%s' xmlns:unused='%s' version='7.0'>
                  <!-- %s -->
                  <entity class='demo.Book'>
                    <m:table xmlns:m='%s' name='%s' comment='convert'/>
                    <secondary-table xmlns='%s' comment='nested'/>
                    <attributes><many-to-many orphan-removal='true'/></attributes>
                    <m:table xmlns:m='urn:foreign' comment='keep'><column xmlns='%s' comment='keep'/></m:table>
                    <unknown><table comment='keep'/></unknown>
                  </entity>
                </entity-mappings>
                """.formatted(HTTP, HTTP, HTTP, HTTP, HTTPS, HTTP, HTTP, HTTP);
        String expected = input.replace("xmlns='" + HTTP, "xmlns='" + HTTPS)
                .replace("xmlns:m='" + HTTP, "xmlns:m='" + HTTPS)
                .replace("xmlns:unused='" + HTTP, "xmlns:unused='" + HTTPS)
                .replace("version='7.0'", "version='8.0'")
                .replace(" comment='convert'/>", ">\n        <m:comment>convert</m:comment>\n    </m:table>")
                .replace(" comment='nested'/>", ">\n        <comment>nested</comment>\n    </secondary-table>")
                .replace(" orphan-removal='true'", "");
        var result = run(new MigrateMappingXml(), input);
        assertEquals(expected, result.text());
        assertTrue(result.rows().isEmpty());
    }

    @Test void absentAndUnrelatedSchemaPairsAndOrdinaryValuesArePreserved() {
        for (String extra : List.of("", "s:schemaLocation='urn:other other.xsd'", "f:schemaLocation='custom'")) {
            String input = "<entity-mappings xmlns='" + HTTP + "' xmlns:s='http://www.w3.org/2001/XMLSchema-instance'"
                    + " xmlns:f='urn:foreign' version='8.0' " + extra + "><description>" + HTTP + "</description></entity-mappings>";
            assertEquals(input.replace("xmlns='" + HTTP, "xmlns='" + HTTPS), run(new MigrateMappingXml(), input).text());
        }
    }

    @Test void bothNamespaceFormsCountAsDuplicatePairs() {
        for (String namespace : List.of(HTTP, HTTPS)) {
            String input = "<entity-mappings xmlns='" + namespace + "' version='7.0'"
                    + " xmlns:s='http://www.w3.org/2001/XMLSchema-instance' s:schemaLocation='"
                    + HTTP + " " + HTTPS + "/mapping-7.0.xsd " + HTTPS + " " + HTTPS + "/mapping-7.0.xsd'/>";
            var result = run(new MigrateMappingXml(), input);
            assertEquals(input, result.text());
            assertEquals(1, result.rows().size());
            assertEquals("XML_SCHEMA_CONFLICT", result.rows().getFirst().getReasonCode());
        }
    }

    @Test void invalidMetadataRejectsBothNamespaceFormsBeforeContentChanges() {
        for (String namespace : List.of(HTTP, HTTPS)) {
            for (String pair : List.of(namespace, namespace + " custom.xsd", namespace + " " + HTTPS + "/mapping-8.0.xsd")) {
                String input = "<entity-mappings xmlns='" + namespace + "' version='7.0'"
                        + " xmlns:s='http://www.w3.org/2001/XMLSchema-instance' s:schemaLocation='" + pair
                        + "'><entity><table comment='convert'/></entity></entity-mappings>";
                var result = run(new MigrateMappingXml(), input);
                assertEquals(input, result.text());
                assertEquals(1, result.rows().size());
                assertEquals(pair.equals(namespace) ? "XML_SCHEMA_CONFLICT" : "XML_SCHEMA_LOCATION_UNSUPPORTED",
                        result.rows().getFirst().getReasonCode());
            }
            for (String version : List.of("", "9.0")) {
                String input = "<entity-mappings xmlns='" + namespace + "' version='" + version + "'/>";
                var result = run(new MigrateMappingXml(), input);
                assertEquals(input, result.text());
                assertEquals("XML_VERSION_UNSUPPORTED", result.rows().getFirst().getReasonCode());
            }
        }
    }

    @Test void conflictRollsBackBindingsContentAndMetadataAtOriginalLocations() {
        for (String namespace : List.of(HTTP, HTTPS)) {
            String input = "<!-- original -->\n<entity-mappings xmlns='" + namespace + "' version='7.0'"
                    + " xmlns:s='http://www.w3.org/2001/XMLSchema-instance' s:schemaLocation='" + HTTP + " " + HTTP + "/mapping-7.0.xsd'>\n"
                    + "<entity><attributes><many-to-many orphan-removal='true'/></attributes></entity>\n"
                    + "<entity><table comment='old'><comment>new</comment></table></entity>\n</entity-mappings>";
            var result = run(composite("orm80"), input);
            assertEquals(input, result.text());
            assertEquals(1, result.rows().size());
            var row = result.rows().getFirst();
            assertEquals("XML_COMMENT_CONFLICT", row.getReasonCode());
            assertEquals(4, row.getLine());
            assertEquals(16, row.getColumn());
        }
    }
}
