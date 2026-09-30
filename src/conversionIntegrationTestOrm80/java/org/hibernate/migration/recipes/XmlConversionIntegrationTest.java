/* SPDX-License-Identifier: Apache-2.0 */
package org.hibernate.migration.recipes;

import jakarta.persistence.Persistence;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.BootstrapServiceRegistryBuilder;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.migration.testing.FixtureBundle;
import org.hibernate.migration.testing.ValidationEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/// Consumes verified, recipe-generated XML using the pinned target Hibernate runtime.
/// @author Steve Ebersole
class XmlConversionIntegrationTest {
    @TempDir Path classes;

    private Path fixture(String name) throws Exception {
        var environments = ValidationEnvironment.configured();
        var context = environments.primary();
        Path root = Path.of(System.getProperty("transformedFixtures"));
        var entries = FixtureBundle.validate(root, context.migration(), context.target(), FixtureBundle.catalog(context.migration()), environments);
        var entry = entries.stream().filter(e -> e.fixture().equals(name)).findFirst().orElseThrow();
        Path sources = root.resolve(entry.id()).resolve("sources");
        List<String> args = new ArrayList<>(List.of("-proc:none", "--release", environments.value(context.target() + ".javaVersion"),
                "-classpath", System.getProperty("java.class.path"), "-d", classes.toString()));
        for (String source : entry.sources()) {
            if (source.endsWith(".java")) args.add(sources.resolve(source).toString());
            else {
                Path resource = classes.resolve(source);
                Files.createDirectories(resource.getParent());
                Files.copy(sources.resolve(source), resource);
            }
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        return sources;
    }

    @Test void migratedPackageDescriptorRegistersPackageAnnotations() throws Exception {
        fixture("persistence-xml");
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, previous)) {
            Thread.currentThread().setContextClassLoader(loader);
            Map<String, Object> properties = Map.of(
                    "hibernate.connection.url", "jdbc:h2:mem:xml_package_test;DB_CLOSE_DELAY=-1",
                    "hibernate.connection.driver_class", "org.h2.Driver",
                    "hibernate.hbm2ddl.auto", "create-drop");
            try (var emf = Persistence.createEntityManagerFactory("xml-package-test", properties)) {
                assertTrue(emf.unwrap(SessionFactory.class).getDefinedFilterNames().contains("packageFilter"));
                assertEquals("xmlfixture.Item", emf.getMetamodel().getEntities().iterator().next().getJavaType().getName());
            }
        }
        finally { Thread.currentThread().setContextClassLoader(previous); }
    }

    @Test void migratedCommentsReachHibernateMappingMetadata() throws Exception {
        fixture("mapping-xml");
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            var bootstrap = new BootstrapServiceRegistryBuilder().applyClassLoader(loader).build();
            var registry = new StandardServiceRegistryBuilder(bootstrap)
                    .applySetting("hibernate.connection.url", "jdbc:h2:mem:xml_mapping_test;DB_CLOSE_DELAY=-1")
                    .applySetting("hibernate.connection.driver_class", "org.h2.Driver").build();
            try (var stream = Files.newInputStream(classes.resolve("mapping.xml"))) {
                var metadata = new MetadataSources(registry).addInputStream(stream).buildMetadata();
                var table = metadata.getEntityBinding("xmlfixture.MappedItem").getTable();
                assertEquals("Table & details", table.getComment());
                var column = table.getColumns().stream().filter(c -> c.getName().equals("label")).findFirst().orElseThrow();
                assertEquals("Label <value>", column.getComment());
                try (var factory = metadata.buildSessionFactory()) {
                    assertEquals("xmlfixture.MappedItem", factory.getMetamodel().getEntities().iterator().next().getJavaType().getName());
                }
            }
            finally { StandardServiceRegistryBuilder.destroy(registry); }
        }
    }
}
