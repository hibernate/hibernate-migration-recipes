package org.hibernate.migration.recipes.jpa4;

import org.hibernate.migration.testing.ApiValidation;

import org.hibernate.migration.testing.FixtureBundle;
import java.util.LinkedHashMap;
import java.util.Map;

/// Generates runtime XML fixtures through the selected recipes, never by hand-editing target XML.
/// @author Steve Ebersole
final class XmlRuntimeFixtures {
    private XmlRuntimeFixtures() {}

    static Map<String, String> convert(FixtureBundle.Entry entry) {
        Map<String, String> javaSources = new LinkedHashMap<>();
        String xmlPath;
        String input;
        String sourceSchema;
        String targetSchema;
        if (entry.fixture().equals("persistence-xml")) {
            javaSources.put("xmlfixture/Item.java", """
                    package xmlfixture;
                    /// Runtime entity for XML package discovery.
                    /// @author Steve Ebersole
                    @jakarta.persistence.Entity
                    public class Item {
                        @jakarta.persistence.Id public Long id;
                    }
                    """);
            javaSources.put("xmlfixture/package-info.java", """
                    /// Package annotation consumed through the migrated descriptor.
                    /// @author Steve Ebersole
                    @org.hibernate.annotations.FilterDef(name="packageFilter", defaultCondition="1=1")
                    package xmlfixture;
                    """);
            xmlPath = "META-INF/persistence.xml";
            input = """
                    <persistence xmlns="https://jakarta.ee/xml/ns/persistence" version="3.2">
                        <persistence-unit name="xml-package-test" transaction-type="RESOURCE_LOCAL">
                            <class>xmlfixture.package-info</class>
                            <class>xmlfixture.Item</class>
                            <exclude-unlisted-classes>true</exclude-unlisted-classes>
                        </persistence-unit>
                    </persistence>
                    """;
            sourceSchema = "org/hibernate/jpa/persistence_3_2.xsd";
            targetSchema = "org/hibernate/jpa/persistence_4_0.xsd";
        }
        else if (entry.fixture().equals("mapping-xml")) {
            javaSources.put("xmlfixture/MappedItem.java", """
                    package xmlfixture;
                    /// Runtime entity configured through migrated Hibernate XML.
                    /// @author Steve Ebersole
                    public class MappedItem {
                        public Long id;
                        public String label;
                    }
                    """);
            xmlPath = "mapping.xml";
            input = """
                    <entity-mappings xmlns="http://www.hibernate.org/xsd/orm/mapping" version="7.0">
                        <entity class="xmlfixture.MappedItem" access="FIELD">
                            <table name="mapped_items" comment="Table &amp; details"/>
                            <attributes>
                                <id name="id"><column name="id"/></id>
                                <basic name="label"><column name="label" comment="Label &lt;value&gt;"/></basic>
                            </attributes>
                        </entity>
                    </entity-mappings>
                    """;
            sourceSchema = "org/hibernate/xsd/mapping/mapping-7.0.xsd";
            targetSchema = "org/hibernate/xsd/mapping/mapping-8.0.xsd";
        }
        else throw new IllegalArgumentException("Unknown XML fixture: " + entry.fixture());
        var javaResult = ApiValidation.run(ApiValidation.composite(entry.recipe()), javaSources, ApiValidation.environments().context(entry.variant()));
        if (!javaResult.skipped().isEmpty()) throw new IllegalStateException("Unexpected Java skips for " + entry.id());
        var xmlResult = XmlRecipeSupport.run(ApiValidation.composite(entry.recipe()), input);
        if (!xmlResult.rows().isEmpty()) throw new IllegalStateException("Unexpected XML skips for " + entry.id());
        try {
            XmlRecipeSupport.validate(input, sourceSchema);
            XmlRecipeSupport.validate(xmlResult.text(), targetSchema);
        }
        catch (Exception e) { throw new IllegalStateException("Invalid XML fixture: " + entry.id(), e); }
        Map<String, String> result = new LinkedHashMap<>(javaResult.files());
        result.put(xmlPath, xmlResult.text());
        return result;
    }
}
