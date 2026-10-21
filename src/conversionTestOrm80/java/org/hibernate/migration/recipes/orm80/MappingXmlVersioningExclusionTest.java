package org.hibernate.migration.recipes.orm80;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.hibernate.migration.recipes.jpa4.XmlRecipeSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/// Verifies relocation of named versioning exclusions to their attribute mappings.
///
/// @author Steve Ebersole
class MappingXmlVersioningExclusionTest {
    private static final String NS = "https://www.hibernate.org/xsd/orm/mapping";

    @Test void relocatesAllSupportedMappingsInSchemaOrder() throws Exception {
        for (String owner : List.of("entity", "mapped-superclass", "embeddable")) {
            String body = """
                    <basic name='basic'><column name='value'/><mutable>true</mutable></basic>
                    <many-to-one name='toOne'><on-delete>CASCADE</on-delete></many-to-one>
                    <one-to-many name='children'><on-delete>CASCADE</on-delete></one-to-many>
                    <one-to-one name='one'/>
                    <many-to-many name='many'><on-delete>CASCADE</on-delete></many-to-many>
                    <element-collection name='elements'><collection-table name='element_values'/><on-delete>CASCADE</on-delete></element-collection>
                    <embedded name='embedded'/>
                    <any name='any'><discriminator><column name='kind'/><mapping value='book'>Book</mapping></discriminator><key><column name='ref'/></key></any>
                    <many-to-any name='anyMany'><discriminator><column name='kind'/><mapping value='book'>Book</mapping></discriminator><key><column name='ref'/></key><join-table/></many-to-any>
                    """;
            String legacyAttributes = body;
            for (String name : List.of("basic", "embedded", "elements", "toOne", "one", "many", "children", "any", "anyMany")) {
                legacyAttributes = legacyAttributes.replace("name='" + name + "'", "name='" + name + "' optimistic-lock='false'");
                body += "<excluded-from-versioning name='" + name + "'/>";
            }
            for (String mappings : List.of(body, legacyAttributes)) {
                String input = root("<" + owner + " class='demo.Book'><attributes>" + mappings + "</attributes></" + owner + ">");
                if (mappings.equals(legacyAttributes)) {
                    validate(input.replace("<mutable>true</mutable>", "").replace("<on-delete>CASCADE</on-delete>", "").replace(NS, NS.replace("https:", "http:")).replace("version='8.0'", "version='7.0'"),
                            "org/hibernate/xsd/mapping/mapping-7.0.xsd");
                }
                for (var recipe : List.of(new MigrateMappingXml(), composite("orm80"))) {
                    var result = run(recipe, input);
                    assertTrue(result.rows().isEmpty(), result.rows().toString());
                    assertFalse(result.text().contains("excluded-from-versioning"));
                    assertEquals(9, result.text().split("<exclude-from-versioning", -1).length - 1);
                    validate(result.text(), "org/hibernate/xsd/mapping/mapping-8.0.xsd");
                }
            }
        }
    }

    @Test void preservesPrefixesAndComments() {
        String input = """
                <m:entity-mappings xmlns:m='http://www.hibernate.org/xsd/orm/mapping' version='7.0'>
                  <m:entity class='Book'><m:attributes>
                    <m:basic name='title' optimistic-lock='false'/>
                    <!-- keep position --><m:excluded-from-versioning name='title'><!-- keep content --></m:excluded-from-versioning>
                  </m:attributes></m:entity>
                </m:entity-mappings>
                """;
        var result = run(new MigrateMappingXml(), input);
        assertTrue(result.rows().isEmpty());
        assertTrue(result.text().contains("<m:exclude-from-versioning><!-- keep content -->"), result.text());
        assertTrue(result.text().contains("</m:exclude-from-versioning>"));
        assertFalse(result.text().contains("optimistic-lock="));
        assertTrue(result.text().contains("<!-- keep position -->"));
        assertTrue(result.text().contains("version='8.0'"));
    }

    @Test void legacyBooleanExclusionsAndExplicitInclusion() throws Exception {
        for (String value : List.of("false", "0", "  false  ")) {
            String input = root("<entity class='Book'><attributes><basic name='x' optimistic-lock='" + value
                    + "'><mutable>true</mutable></basic><basic name='y' optimistic-lock='true'/></attributes></entity>");
            var result = run(new MigrateMappingXml(), input);
            assertTrue(result.rows().isEmpty());
            assertTrue(result.text().contains("<exclude-from-versioning/><mutable>"), result.text());
            assertTrue(result.text().contains("optimistic-lock='true'"));
            validate(result.text(), "org/hibernate/xsd/mapping/mapping-8.0.xsd");
        }
    }

    @Test void unresolvedTargetsAndConflictsRollbackTheWholeDocument() {
        for (String body : List.of(
                "<basic name='x'/><excluded-from-versioning name='missing'/>",
                "<basic name='x'/><basic name='x'/><excluded-from-versioning name='x'/>",
                "<id name='x'/><excluded-from-versioning name='x'/>",
                "<basic name='x' optimistic-lock='true'/><excluded-from-versioning name='x'/>",
                "<basic name='x' optimistic-lock='1'/><excluded-from-versioning name='x'/>",
                "<basic name='x'/><excluded-from-versioning name='x' other='data'/>",
                "<basic name='x'/><excluded-from-versioning name='x'>text</excluded-from-versioning>")) {
            String input = root("<entity class='Book'><table comment='convert'/><attributes>" + body + "</attributes></entity>");
            var result = run(new MigrateMappingXml(), input);
            assertEquals(input, result.text());
            assertEquals(1, result.rows().size());
            assertEquals("excluded-from-versioning", result.rows().getFirst().getSubject());
        }
    }

    @Test void redundantEntriesAndExistingNestedExclusionsAreIdempotent() {
        String input = root("<entity class='Book'><attributes><basic name='x'><exclude-from-versioning/></basic>"
                + "<excluded-from-versioning name='x'/><excluded-from-versioning name='x'/></attributes></entity>");
        var result = run(new MigrateMappingXml(), input);
        assertTrue(result.rows().isEmpty());
        assertEquals(root("<entity class='Book'><attributes><basic name='x'><exclude-from-versioning/></basic></attributes></entity>"), result.text());
    }

    @Test void foreignNamespacesAndUnrelatedPathsAreUntouched() {
        String input = root("<excluded-from-versioning name='x'/><entity class='Book'><attributes>"
                + "<basic name='x'/><f:excluded-from-versioning xmlns:f='urn:foreign' name='x'/>"
                + "<unknown><excluded-from-versioning name='x'/></unknown></attributes></entity>");
        assertEquals(input, run(new MigrateMappingXml(), input).text());
    }

    @Test void namespaceRebindingAndEncodedNames() throws Exception {
        String input = root("<entity class='Book'><attributes><basic xmlns:x='urn:foreign' name='a&amp;b'/>"
                + "<x:excluded-from-versioning xmlns:x='" + NS + "' name='a&#38;b'/></attributes></entity>");
        var result = run(new MigrateMappingXml(), input);
        assertTrue(result.rows().isEmpty());
        assertTrue(result.text().contains("<x:exclude-from-versioning xmlns:x='" + NS + "'/>"), result.text());
        validate(result.text(), "org/hibernate/xsd/mapping/mapping-8.0.xsd");
    }

    @Test void invalidBooleansAndNestedConflictsReportOriginalCoordinates() {
        for (String mapping : List.of("<basic name='x' optimistic-lock='${value}'/>",
                "<basic name='x' optimistic-lock='true'><exclude-from-versioning/></basic>",
                "<basic name='x' optimistic-lock='false'><exclude-from-versioning/><exclude-from-versioning/></basic>")) {
            String input = root("<entity class='Book'><attributes>\n  " + mapping + "</attributes></entity>");
            var result = run(new MigrateMappingXml(), input);
            assertEquals(input, result.text());
            assertEquals(1, result.rows().size());
            assertEquals(2, result.rows().getFirst().getLine());
            assertEquals("optimistic-lock", result.rows().getFirst().getSubject());
        }
    }

    private static String root(String body) {
        return "<entity-mappings xmlns='" + NS + "' version='8.0'>" + body + "</entity-mappings>";
    }
}
