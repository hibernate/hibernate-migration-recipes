/* SPDX-License-Identifier: Apache-2.0 */
package org.hibernate.migration.recipes.jpa4;

import org.hibernate.migration.recipes.table.SkippedMigrations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openrewrite.*;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.xml.XmlParser;
import org.openrewrite.xml.tree.Xml;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;

/// Validates emitted descriptors with schemas bundled in the pinned published ORM release.
/// @author Steve Ebersole
class PersistenceXmlHardeningTest {
    static final String NS = "https://jakarta.ee/xml/ns/persistence";
    static final String XSI = "http://www.w3.org/2001/XMLSchema-instance";

    @ParameterizedTest @ValueSource(strings = {"3.0", "3.1", "3.2"})
    void schemasAliasesAndUnrelatedMetadata(String version) throws Exception {
        for (String recipe : List.of("leaf", "org.hibernate.migration.recipes.jpa4", "org.hibernate.migration.recipes.orm8")) {
            String input = "<?xml version=\"1.0\"?>\r\n<!-- keep -->\r\n<p:persistence xmlns:p=\"" + NS + "\" xmlns:s=\"" + XSI + "\" version=\"" + version + "\" s:schemaLocation=\"urn:other  other.xsd\n  " + NS + "\t" + NS + "/persistence_" + version.replace('.', '_') + ".xsd  \">\r\n<p:persistence-unit name=\"demo\"/>\r\n</p:persistence>";
            validate(input, version);
            var result = run(recipe.equals("leaf") ? new UpdatePersistenceXmlVersion() : ApiValidation.composite(recipe), input);
            assertEquals(input.replace("version=\"" + version + "\"", "version=\"4.0\"").replace("persistence_" + version.replace('.', '_') + ".xsd", "persistence_4_0.xsd"), result.text);
            assertTrue(result.rows.isEmpty());
            validate(result.text, "4.0");
        }
    }

    @Test void missingSchemaIsNotInvented() throws Exception {
        String input = "<persistence xmlns=\"" + NS + "\" version=\"3.2\"><persistence-unit name=\"demo\"/></persistence>";
        validate(input, "3.2");
        String output = run(new UpdatePersistenceXmlVersion(), input).text;
        assertEquals(input.replace("3.2", "4.0"), output);
        validate(output, "4.0");
    }

    @Test void unrelatedSchemaPairsAndForeignAttributesArePreserved() {
        for (String attributes : List.of("s:schemaLocation=\"urn:other other.xsd\"", "xmlns:o=\"urn:other\" o:schemaLocation=\"custom\"")) {
            String input = root("3.2", attributes);
            var result = run(new UpdatePersistenceXmlVersion(), input);
            assertEquals(input.replace("version=\"3.2\"", "version=\"4.0\""), result.text);
            assertTrue(result.rows.isEmpty());
        }
    }

    @Test void conflictsAndUnrelatedDocuments() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("<persistence version=\"3.2\"/>", "XML_NAMESPACE_UNSUPPORTED");
        cases.put("<persistence xmlns=\"urn:foreign\" version=\"3.2\"/>", "XML_NAMESPACE_UNSUPPORTED");
        cases.put(root("", ""), "XML_VERSION_UNSUPPORTED");
        cases.put(root("5.0", ""), "XML_VERSION_UNSUPPORTED");
        cases.put(root("3.2", "s:schemaLocation=\"" + NS + "\""), "XML_SCHEMA_CONFLICT");
        cases.put(root("3.2", "s:schemaLocation=\"" + NS + " a.xsd " + NS + " b.xsd\""), "XML_SCHEMA_LOCATION_UNSUPPORTED");
        cases.put(root("3.2", "s:schemaLocation=\"" + NS + " " + NS + "/persistence_3_2.xsd " + NS + " " + NS + "/persistence_3_2.xsd\""), "XML_SCHEMA_CONFLICT");
        cases.put(root("3.2", "s:schemaLocation=\"" + NS + " " + NS + "/persistence_3_1.xsd\""), "XML_SCHEMA_LOCATION_UNSUPPORTED");
        cases.put(root("4.0", "s:schemaLocation=\"" + NS + " " + NS + "/persistence_3_2.xsd\""), "XML_SCHEMA_LOCATION_UNSUPPORTED");
        cases.put(root("3.2", "s:noNamespaceSchemaLocation=\"custom.xsd\""), "XML_SCHEMA_CONFLICT");
        cases.put(root("3.2", "missing:schemaLocation=\"custom.xsd\""), "XML_SCHEMA_CONFLICT");
        cases.put(root("3.2", "xmlns:t=\"" + XSI + "\" s:schemaLocation=\"urn:other other.xsd\" t:schemaLocation=\"urn:other other.xsd\""), "XML_SCHEMA_CONFLICT");
        cases.put(root("3.2", "schemaLocation=\"custom.xsd\""), "XML_SCHEMA_CONFLICT");
        for (var entry : cases.entrySet()) {
            String input = "<!--original-->\n" + entry.getKey();
            var result = run(new UpdatePersistenceXmlVersion(), input);
            assertEquals(input, result.text);
            assertEquals(1, result.rows.size());
            assertEquals(entry.getValue(), result.rows.getFirst().getReasonCode(), input);
            assertEquals(2, result.rows.getFirst().getLine());
            assertEquals(1, result.rows.getFirst().getColumn());
            assertEquals("config/custom.xml", result.rows.getFirst().getSourcePath());
        }
        for (String input : List.of("<unrelated version=\"3.2\"/>", root("4.0", ""), "<wrapper>" + root("3.2", "") + "</wrapper>")) {
            var result = run(new UpdatePersistenceXmlVersion(), input);
            assertEquals(input, result.text);
            assertTrue(result.rows.isEmpty());
        }
    }

    private static String root(String version, String attributes) {
        return "<persistence xmlns=\"" + NS + "\" xmlns:s=\"" + XSI + "\" " + (version.isEmpty() ? "" : "version=\"" + version + "\" ") + attributes + "/>";
    }
    private static Outcome run(Recipe recipe, String input) {
        List<Throwable> errors = new ArrayList<>();
        var ctx = new InMemoryExecutionContext(errors::add);
        SourceFile source = XmlParser.builder().build().parse(ctx, input).findFirst().orElseThrow().withSourcePath(Path.of("config/custom.xml"));
        assertInstanceOf(Xml.Document.class, source);
        var run = recipe.run(new InMemoryLargeSourceSet(List.of(source)), ctx);
        var changes = run.getChangeset().getAllResults();
        String output = changes.isEmpty() ? input : changes.getFirst().getAfter().printAll();
        SourceFile reparsed = XmlParser.builder().build().parse(ctx, output).findFirst().orElseThrow();
        assertTrue(recipe.run(new InMemoryLargeSourceSet(List.of(reparsed)), new InMemoryExecutionContext(errors::add)).getChangeset().getAllResults().isEmpty());
        assertTrue(errors.isEmpty(), errors.toString());
        return new Outcome(output, run.getDataTableRows(SkippedMigrations.class));
    }
    private static void validate(String xml, String version) throws Exception {
        try (ZipFile jar = new ZipFile(ApiValidation.environments().value(ApiValidation.environments().primary().target() + ".ormJar"))) {
            String resource = "org/hibernate/jpa/persistence_" + version.replace('.', '_') + ".xsd";
            var entry = jar.getEntry(resource);
            assertNotNull(entry, resource);
            SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            try (InputStream stream = jar.getInputStream(entry)) {
                var validator = factory.newSchema(new StreamSource(stream)).newValidator();
                validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                validator.validate(new StreamSource(new StringReader(xml)));
            }
        }
    }
    private record Outcome(String text, List<SkippedMigrations.Row> rows) {}
}
