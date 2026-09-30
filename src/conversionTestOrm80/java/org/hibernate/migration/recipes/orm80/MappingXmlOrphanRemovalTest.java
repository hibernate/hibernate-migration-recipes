/* SPDX-License-Identifier: Apache-2.0 */
package org.hibernate.migration.recipes.orm80;

import org.junit.jupiter.api.Test;
import org.openrewrite.Recipe;
import java.util.List;
import static org.hibernate.migration.recipes.jpa4.XmlRecipeSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/// Verifies removal of ignored many-to-many orphan removal without affecting supported associations.
///
/// @author Steve Ebersole
class MappingXmlOrphanRemovalTest {
    private static final String NS = "http://www.hibernate.org/xsd/orm/mapping";

    @Test void allBooleanFormsAndMappingContextsValidate() throws Exception {
        for (String context : List.of("entity", "mapped-superclass", "embeddable")) {
            for (String value : List.of("true", "false", "1", "0")) {
                String input = root("7.0", "<m:" + context + " class='demo.Book'><m:attributes>"
                        + "<m:one-to-many name='children' orphan-removal='true'/>"
                        + "<m:one-to-one name='detail' orphan-removal='true'/>"
                        + "<m:many-to-many name='related' orphan-removal='" + value + "'/></m:attributes></m:" + context + ">");
                validate(input, schema("7.0"));
                for (Recipe recipe : List.of(new MigrateMappingXml(), composite("orm80"))) {
                    var result = run(recipe, input);
                    assertEquals(input.replace("version='7.0'", "version='8.0'")
                            .replace("<m:many-to-many name='related' orphan-removal='" + value + "'/>", "<m:many-to-many name='related'/>"), result.text());
                    assertTrue(result.rows().isEmpty());
                    validate(result.text(), schema("8.0"));
                }
            }
        }
    }

    @Test void removesRegardlessOfValueAndCombinesWithCommentsAndMetadata() throws Exception {
        for (String version : List.of("7.0", "8.0")) {
            for (String value : List.of("true", "false", "", "${ignored}")) {
                String input = root(version, "<m:entity class='demo.Book'><m:attributes><m:many-to-many name='related' orphan-removal=\"" + value
                        + "\"><m:join-table name='related_book' comment='keep &amp; convert'/></m:many-to-many></m:attributes></m:entity>")
                        .replace("version=", "xmlns:s='http://www.w3.org/2001/XMLSchema-instance' s:schemaLocation='" + NS + " https://www.hibernate.org/xsd/orm/mapping/mapping-" + version + ".xsd' version=");
                var result = run(new MigrateMappingXml(), input);
                assertFalse(result.text().contains("orphan-removal"));
                assertTrue(result.text().contains("<m:comment>keep &amp; convert</m:comment>"));
                assertTrue(result.text().contains("version='8.0'"));
                assertTrue(result.text().contains("mapping-8.0.xsd"));
                assertTrue(result.rows().isEmpty());
                validate(result.text(), schema("8.0"));
            }
        }
    }

    @Test void namespacesAndUnrelatedContextsArePreserved() {
        String input = root("8.0", "<m:many-to-many orphan-removal='true'/><m:entity class='demo.Book'><m:attributes>"
                + "<m:many-to-many xmlns:f='urn:foreign' name='related' f:orphan-removal='true'/>"
                + "<m:many-to-many xmlns:m='urn:foreign' name='other' orphan-removal='true'/>"
                + "<m:unknown><m:many-to-many orphan-removal='true'/></m:unknown>"
                + "</m:attributes></m:entity>");
        assertEquals(input, run(new MigrateMappingXml(), input).text());
        String jpa = input.replace(NS, "https://jakarta.ee/xml/ns/persistence/orm").replace("version='8.0'", "version='4.0'");
        assertEquals(jpa, run(new MigrateMappingXml(), jpa).text());
        assertEquals(jpa, run(composite("jpa4"), jpa).text());
    }

    @Test void ineligibleMetadataAndContentConflictsCancelRemoval() {
        String body = "<m:entity class='demo.Book'><m:attributes><m:many-to-many name='related' orphan-removal='true'/></m:attributes></m:entity>";
        for (String version : List.of("3.1.0", "9.0")) {
            String input = root(version, body);
            assertEquals(input, run(new MigrateMappingXml(), input).text());
        }
        String invalidSchema = root("7.0", body).replace("version=", "xmlns:s='http://www.w3.org/2001/XMLSchema-instance' s:schemaLocation='" + NS + " custom.xsd' version=");
        assertEquals(invalidSchema, run(new MigrateMappingXml(), invalidSchema).text());
        // The conflict follows a convertible association, so rollback must undo a proposed removal.
        String input = root("7.0", body + "<m:entity class='demo.Other'><m:table comment='old'><m:comment>new</m:comment></m:table></m:entity>");
        var result = run(composite("orm80"), input);
        assertEquals(input, result.text());
        assertEquals(1, result.rows().size());
        assertEquals("XML_COMMENT_CONFLICT", result.rows().getFirst().getReasonCode());
    }

    private static String root(String version, String body) {
        return "<m:entity-mappings xmlns:m='" + NS + "' version='" + version + "'>" + body + "</m:entity-mappings>";
    }
    private static String schema(String version) { return "org/hibernate/xsd/mapping/mapping-" + version + ".xsd"; }
}
