package org.hibernate.migration.recipes.orm80;

import org.junit.jupiter.api.Test;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.util.*;
import static org.hibernate.migration.recipes.jpa4.XmlRecipeSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/// Verifies all comment-bearing schema types, ordering, escaping, and conservative conflicts.
/// @author Steve Ebersole
class MappingXmlCommentsTest {
    private static final String NS = "http://www.hibernate.org/xsd/orm/mapping";

    @Test void allAffectedTypesValidateWithTargetOrdering() throws Exception {
        String input = root("7.0", """
                <table-generator name="ids" comment="generator"><description>keep</description><unique-constraint><column-name>id</column-name></unique-constraint></table-generator>
                <entity class="demo.Book">
                  <table name="book" comment="table"><check-constraint constraint="id &gt; 0"/><index column-list="id"/></table>
                  <secondary-table name="details" comment="secondary"><primary-key-join-column name="id"/><index column-list="id"/></secondary-table>
                  <attributes>
                    <id name="id"><column name="id" comment="id"/></id>
                    <basic name="title"><column name="title" comment="column"><check-constraint constraint="length(title) &gt; 0"/></column></basic>
                    <many-to-one name="owner"><join-table name="owner_book" comment="join"><join-column name="book_id" comment="join-column"/><inverse-join-column name="owner_id" comment="inverse"/></join-table></many-to-one>
                    <element-collection name="labels"><collection-table name="labels" comment="collection"><join-column name="book_id"/><index column-list="book_id"/></collection-table></element-collection>
                  </attributes>
                </entity>
                """);
        validate(input, schemaPath("7.0"));
        var result = run(composite("orm80"), input);
        assertTrue(result.rows().isEmpty());
        assertFalse(result.text().contains(" comment="));
        assertEquals(9, occurrences(result.text(), "<comment>"));
        validate(result.text(), schemaPath("8.0"));
        String leaf = run(new MigrateMappingXml(), input).text();
        assertEquals(result.text(), leaf);
        assertEquals(input, run(composite("jpa4"), input).text());
    }

    @Test void namespaceAliasesAndEscapingPreserveSemanticText() throws Exception {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("A &amp; B &lt;tag&gt; &quot;quote&quot; &apos;single&apos;", "A & B <tag> \"quote\" 'single'");
        values.put("&#x1F642; café &#13;&#10;&#9;", "🙂 café \r\n\t");
        values.put("line1\r\nline2\tend", "line1 line2 end");
        values.put("", "");
        values.put("   ", "   ");
        for (var value : values.entrySet()) {
            String input = "<m:entity-mappings xmlns:m='" + NS + "' version='8.0'><m:entity class='demo.Book'><m:table comment=\"" + value.getKey() + "\"/></m:entity></m:entity-mappings>";
            var result = run(new MigrateMappingXml(), input);
            assertTrue(result.rows().isEmpty());
            assertFalse(result.text().contains(" comment="));
            var parsed = parse(result.text().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(value.getValue(), parsed.getElementsByTagNameNS(NS, "comment").item(0).getTextContent());
            validate(result.text(), schemaPath("8.0"));
        }
    }

    @Test void conflictsAndEquivalentComments() {
        String input = root("7.0", """
                <entity class="demo.Book">
                  <table comment="same &amp; value"><comment>same &#38; value</comment></table>
                  <secondary-table name="a" comment="old"><comment>new</comment></secondary-table>
                  <secondary-table name="b" comment="two"><comment>two</comment><comment>two</comment></secondary-table>
                  <secondary-table name="c" comment="ok"/>
                </entity>
                """);
        var result = run(composite("orm80"), input);
        assertEquals(input, result.text());
        assertEquals(2, result.rows().size());
        assertEquals(List.of(4, 5), result.rows().stream().map(r -> r.getLine()).toList());
        assertTrue(result.rows().stream().allMatch(r -> r.getReasonCode().equals("XML_COMMENT_CONFLICT") && r.getColumn() == 29));
    }

    @Test void aConflictingParentRetainsItsWholeContent() {
        String conflicting = "<join-table comment='old'><comment>new</comment><join-column comment='nested'/></join-table>";
        String input = root("8.0", "<entity class='demo.Book'><attributes><many-to-one name='owner'>" + conflicting
                + "</many-to-one><many-to-one name='other'><join-column comment='convert'/></many-to-one></attributes></entity>");
        var result = run(new MigrateMappingXml(), input);
        assertEquals(input, result.text());
        assertEquals(1, result.rows().size());
    }

    @Test void whitespaceOnlyAndCommentSeparatedEquivalentTextIsPreserved() {
        for (String child : List.of("<comment>   </comment>", "<comment> <!--keep-->  </comment>", "<comment><![CDATA[   ]]></comment>")) {
            String input = root("8.0", "<entity class='demo.Book'><table comment='   '>" + child + "</table></entity>");
            var result = run(new MigrateMappingXml(), input);
            assertEquals(input.replace(" comment='   '", ""), result.text());
            assertTrue(result.rows().isEmpty());
        }
    }

    @Test void unrelatedContextsForeignNamespacesAndUnsupportedVersionsRemain() {
        String body = "<table comment='root is invalid'/><entity class='demo.Book'><unknown><table comment='nested'/></unknown><table xmlns='urn:foreign' comment='foreign'/><table xmlns:f='urn:foreign' f:comment='foreign attribute'/><table xmlns='urn:foreign'><column xmlns='" + NS + "' comment='rebound'/></table></entity>";
        String input = root("8.0", body);
        var result = run(new MigrateMappingXml(), input);
        assertEquals(input, result.text());
        assertTrue(result.rows().isEmpty());
        for (String version : List.of("3.1.0", "9.0")) {
            input = root(version, "<entity class='demo.Book'><table comment='keep'/></entity>");
            result = run(new MigrateMappingXml(), input);
            assertEquals(input, result.text());
            assertEquals("XML_VERSION_UNSUPPORTED", result.rows().getFirst().getReasonCode());
        }
        input = root("7.0", "<entity class='demo.Book'><table comment='keep'/></entity>").replace("version=", "xmlns:s='http://www.w3.org/2001/XMLSchema-instance' s:schemaLocation='" + NS + " custom.xsd' version=");
        assertEquals(input, run(new MigrateMappingXml(), input).text());
    }

    @Test void unresolvedEntitiesAreNotGuessed() {
        String input = "<!DOCTYPE entity-mappings [<!ENTITY custom 'unsafe'>]>" + root("8.0", "<entity class='demo.Book'><table comment='&custom;'/></entity>");
        var result = run(new MigrateMappingXml(), input);
        assertEquals(input, result.text());
        assertEquals("XML_COMMENT_CONFLICT", result.rows().getFirst().getReasonCode());
    }

    @Test void everyReachableSchemaUseOfACommentBearingTypeIsCovered() throws Exception {
        // Derive paths from the published XSD, independently of the production context table.
        Element schema = parse(schema(schemaPath("7.0"))).getDocumentElement();
        Map<String, Element> types = new HashMap<>(), groups = new HashMap<>();
        for (Element child : children(schema)) {
            if (child.getLocalName().equals("complexType")) types.put(child.getAttribute("name"), child);
            if (child.getLocalName().equals("group")) groups.put(child.getAttribute("name"), child);
            if (child.getLocalName().equals("element") && child.getAttribute("name").equals("entity-mappings"))
                types.put("entity-mappings", children(child).stream().filter(e -> e.getLocalName().equals("complexType")).findFirst().orElseThrow());
        }
        Set<String> affected = new HashSet<>();
        for (var entry : types.entrySet()) if (children(entry.getValue()).stream().anyMatch(e -> e.getLocalName().equals("attribute") && e.getAttribute("name").equals("comment"))) affected.add(entry.getKey());
        assertEquals(7, affected.size());
        Map<String, List<String>> paths = new HashMap<>();
        paths.put("entity-mappings", List.of());
        Deque<String> pending = new ArrayDeque<>(List.of("entity-mappings"));
        Set<String> checked = new HashSet<>();
        while (!pending.isEmpty()) {
            String type = pending.removeFirst();
            for (Element element : elements(types.get(type), groups, types)) {
                String target = element.getAttribute("type").replace("orm:", "");
                String name = element.getAttribute("name");
                if (!types.containsKey(target)) continue;
                List<String> path = new ArrayList<>(paths.get(type));
                path.add(name);
                if (affected.contains(target)) {
                    String body = "<" + name + " comment='sample'/>";
                    for (int i = path.size() - 2; i >= 0; i--) body = "<" + path.get(i) + ">" + body + "</" + path.get(i) + ">";
                    String xml = run(new MigrateMappingXml(), root("7.0", body)).text();
                    assertFalse(xml.contains(" comment="), type + "/" + name);
                    assertTrue(xml.contains("<comment>sample</comment>"), type + "/" + name);
                    checked.add(target);
                }
                if (!paths.containsKey(target)) { paths.put(target, path); pending.add(target); }
            }
        }
        assertEquals(affected, checked);
    }

    private static List<Element> elements(Element node, Map<String, Element> groups, Map<String, Element> types) {
        List<Element> result = new ArrayList<>();
        for (Element child : children(node)) {
            switch (child.getLocalName()) {
                case "element" -> result.add(child);
                case "annotation", "attribute", "attributeGroup" -> { }
                case "group" -> result.addAll(elements(groups.get(child.getAttribute("ref").replace("orm:", "")), groups, types));
                case "extension" -> {
                    Element base = types.get(child.getAttribute("base").replace("orm:", ""));
                    if (base != null) result.addAll(elements(base, groups, types));
                    result.addAll(elements(child, groups, types));
                }
                default -> result.addAll(elements(child, groups, types));
            }
        }
        return result;
    }
    private static List<Element> children(Element node) {
        List<Element> result = new ArrayList<>();
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) if (child instanceof Element e) result.add(e);
        return result;
    }
    private static Document parse(byte[] xml) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }
    private static int occurrences(String text, String part) { return (text.length() - text.replace(part, "").length()) / part.length(); }
    private static String schemaPath(String version) { return "org/hibernate/xsd/mapping/mapping-" + version + ".xsd"; }
    private static String root(String version, String body) { return "<entity-mappings xmlns='" + NS + "' version='" + version + "'>\n" + body + "</entity-mappings>"; }
}
