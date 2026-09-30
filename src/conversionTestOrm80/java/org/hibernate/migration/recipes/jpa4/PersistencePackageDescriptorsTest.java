package org.hibernate.migration.recipes.jpa4;

import org.junit.jupiter.api.Test;
import org.openrewrite.Recipe;
import java.util.List;
import static org.hibernate.migration.recipes.jpa4.XmlRecipeSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/// Checks package descriptor recognition, ordering, isolation, and source preservation.
///
/// @author Steve Ebersole
class PersistencePackageDescriptorsTest {
    @Test void interleavedClassesAndMultipleUnitsValidateAfterComposition() throws Exception {
        for (String version : List.of("3.0", "3.1", "3.2")) {
            String input = root(version, """
                    <p:persistence-unit name="one">
                        <p:class>com.acme.package-info</p:class>
                        <!-- between -->
                        <p:class>com.acme.Book</p:class>
                        <p:class>com.other.package-info</p:class>
                        <?keep instruction?>
                        <p:class>com.acme.Author</p:class>
                        <p:exclude-unlisted-classes>true</p:exclude-unlisted-classes>
                    </p:persistence-unit>
                    <p:persistence-unit name="two"><p:class>com.acme.package-info</p:class></p:persistence-unit>
                    """);
            validate(input, schema(version));
            for (Recipe recipe : List.of(new MigratePersistenceXml(), composite("orm80"))) {
                var result = run(recipe, input);
                assertTrue(result.rows().isEmpty());
                String xml = result.text();
                assertTrue(xml.indexOf("com.acme.Author") < xml.indexOf("<p:package-descriptor>com.acme</p:package-descriptor>"));
                assertTrue(xml.indexOf("<p:package-descriptor>com.other") < xml.indexOf("<p:exclude-unlisted-classes>"));
                assertTrue(xml.contains("<!-- between -->"));
                assertTrue(xml.contains("<?keep instruction?>"));
                assertTrue(xml.contains("name=\"two\"><p:package-descriptor>com.acme</p:package-descriptor>"));
                assertFalse(xml.contains("package-info"));
                validate(xml, schema("4.0"));
                assertTrue(xml.contains("version=\"4.0\""));
            }
        }
    }

    @Test void whitespaceCdataAndExistingDescriptorsArePreserved() {
        String input = root("4.0", "<p:persistence-unit name='one'><p:class>  com.acme.package-info \n</p:class><p:class><![CDATA[com.other.package-info]]></p:class><p:class>com.existing.package-info</p:class><p:package-descriptor>com.existing</p:package-descriptor></p:persistence-unit>");
        var result = run(new MigratePersistenceXml(), input);
        assertEquals(root("4.0", "<p:persistence-unit name='one'><p:package-descriptor>com.existing</p:package-descriptor><p:package-descriptor>  com.acme \n</p:package-descriptor><p:package-descriptor><![CDATA[com.other]]></p:package-descriptor></p:persistence-unit>"), result.text());
        assertTrue(result.rows().isEmpty());
    }

    @Test void whitespaceInsideCdataAndReferencesSurvives() {
        for (String value : List.of("<![CDATA[  com.acme.package-info  ]]>", "&#32;com.acme.package-info&#32;")) {
            String input = root("4.0", "<p:persistence-unit name='one'><p:class>" + value + "</p:class></p:persistence-unit>");
            String expectedValue = value.startsWith("<![CDATA[") ? "<![CDATA[  com.acme  ]]>" : " com.acme ";
            var result = run(new MigratePersistenceXml(), input);
            assertEquals(root("4.0", "<p:persistence-unit name='one'><p:package-descriptor>" + expectedValue + "</p:package-descriptor></p:persistence-unit>"), result.text());
            assertTrue(result.rows().isEmpty());
        }
    }

    @Test void unsupportedCandidatesRemainAndUseOriginalCoordinates() {
        String input = root("3.2", """
                <p:persistence-unit name="one">
                  <p:class>com.acme.package-info</p:class>
                  <p:class>com/acme/package-info</p:class>
                  <p:class>package-info</p:class>
                  <p:class>com.acme.package-info.class</p:class>
                  <p:class><nested>com.acme.package-info</nested></p:class>
                </p:persistence-unit>
                """);
        var result = run(composite("orm80"), input);
        assertEquals(input, result.text());
        assertTrue(result.text().contains("<p:class>com/acme/package-info</p:class>"));
        assertEquals(4, result.rows().size());
        assertEquals(List.of(4, 5, 6, 7), result.rows().stream().map(r -> r.getLine()).toList());
        assertTrue(result.rows().stream().allMatch(r -> r.getReasonCode().equals("XML_PACKAGE_DESCRIPTOR_UNSUPPORTED") && r.getColumn() == 3));
    }

    @Test void nestedAndReboundNamespacesAreNotMigrated() {
        String input = root("4.0", "<p:persistence-unit name='one'><p:class xmlns:p='urn:other'>com.acme.package-info</p:class><f:class xmlns:f='urn:foreign'>com.acme.package-info</f:class><p:class>com.acme.Book</p:class></p:persistence-unit><p:persistence-unit xmlns:p='urn:foreign' name='two'><p:class>com.acme.package-info</p:class></p:persistence-unit><wrapper><p:persistence-unit name='nested'><p:class>com.acme.package-info</p:class></p:persistence-unit></wrapper>");
        var result = run(new MigratePersistenceXml(), input);
        assertEquals(input, result.text());
        assertTrue(result.rows().isEmpty());
    }

    @Test void invalidRootMetadataPreventsContentChanges() {
        for (String version : List.of("2.2", "5.0")) {
            String input = root(version, "<p:persistence-unit name='one'><p:class>com.acme.package-info</p:class></p:persistence-unit>");
            var result = run(new MigratePersistenceXml(), input);
            assertEquals(input, result.text());
            assertEquals("XML_VERSION_UNSUPPORTED", result.rows().getFirst().getReasonCode());
        }
        String input = root("3.2", "<p:persistence-unit name='one'><p:class>com.acme.package-info</p:class></p:persistence-unit>").replace("version=", "xmlns:s='http://www.w3.org/2001/XMLSchema-instance' s:schemaLocation='https://jakarta.ee/xml/ns/persistence custom.xsd' version=");
        assertEquals(input, run(new MigratePersistenceXml(), input).text());
    }

    static String root(String version, String body) { return "<p:persistence xmlns:p=\"https://jakarta.ee/xml/ns/persistence\" version=\"" + version + "\">\n" + body + "</p:persistence>"; }
    private static String schema(String version) { return "org/hibernate/jpa/persistence_" + version.replace('.', '_') + ".xsd"; }
}
