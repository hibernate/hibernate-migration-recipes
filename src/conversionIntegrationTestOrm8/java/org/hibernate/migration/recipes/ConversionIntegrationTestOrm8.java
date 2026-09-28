/* SPDX-License-Identifier: Apache-2.0 */
package org.hibernate.migration.recipes;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.hibernate.migration.testing.FixtureBundle;
import org.hibernate.migration.testing.ValidationEnvironment;
import java.util.stream.Stream;
import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/// Boots and executes actual recipe output with published Hibernate ORM and JPA artifacts.
/// @author Steve Ebersole
class ConversionIntegrationTestOrm8 {
    @TempDir Path classes;
    URLClassLoader loader;
    SessionFactory factory;
    Class<?> fixture, item, department;

    static Stream<FixtureBundle.Entry> variants() throws Exception {
        var environments = ValidationEnvironment.configured();
        var context = environments.primary();
        return FixtureBundle.validate(Path.of(System.getProperty("transformedFixtures")), context.migration(), context.target(),
                FixtureBundle.catalog(context.migration()), environments).stream();
    }

    void boot(FixtureBundle.Entry entry) throws Exception {
        var environments = ValidationEnvironment.configured();
        String target = environments.primary().target();
        if (!entry.scenario().equals("hibernate")) throw new IllegalArgumentException("Unknown integration scenario: " + entry.scenario());
        Path source = Path.of(System.getProperty("transformedFixtures"), entry.id(), "sources/fixture/Migrated.java");
        assertTrue(Files.isRegularFile(source), "The fixture generation task must emit fixtures first");
        assertEquals(environments.value(target + ".ormVersion"), org.hibernate.Version.getVersionString());
        assertEquals(Integer.parseInt(environments.value(target + ".javaVersion")), Runtime.version().feature());
        assertTrue(EntityManager.class.getResource("EntityManager.class").toString().contains("jakarta.persistence-api-" + environments.value(target + ".jpaVersion") + ".jar"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "--release", environments.value(target + ".javaVersion"), "-classpath", System.getProperty("java.class.path"), "-d", classes.toString(), source.toString()));
        loader = new URLClassLoader(new java.net.URL[] { classes.toUri().toURL() }, ConversionIntegrationTestOrm8.class.getClassLoader());
        fixture = loader.loadClass("fixture.Migrated");
        item = loader.loadClass("fixture.Migrated$Item");
        department = loader.loadClass("fixture.Migrated$Department");
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(loader);
            factory = new Configuration().addAnnotatedClass(item).addAnnotatedClass(department)
                    .setProperty("hibernate.connection.url", "jdbc:h2:mem:recipe_validation_" + entry.variant() + ";DB_CLOSE_DELAY=-1")
                    .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
                    .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                    .setProperty("hibernate.show_sql", "false")
                    .buildSessionFactory();
        }
        finally { Thread.currentThread().setContextClassLoader(previous); }
    }

    @AfterEach void close() throws Exception {
        if (factory != null) factory.close();
        if (loader != null) loader.close();
    }

    @ParameterizedTest @MethodSource("variants")
    void hqlStatementsAndLegacyCallers(FixtureBundle.Entry entry) throws Exception {
        boot(entry);
        try (Session session = factory.openSession()) {
            var tx = session.beginTransaction();
            assertEquals(1, session.createNamedStatement("hqlInsertValues").setParameter("id", 1L).setParameter("label", "before").executeUpdate());
            assertEquals(1, session.createNamedStatement("hqlInsertSelect").setParameter("id", 2L).setParameter("source", 1L).executeUpdate());
            var update = session.createNamedStatement("hqlUpdate");
            assertEquals(5000, update.getTimeout());
            assertEquals(1, update.setParameter("id", 1L).setParameter("label", "after").executeUpdate());
            assertEquals("after", session.createSelectionQuery("select label from Item where id=1", String.class).getSingleResult());
            assertEquals("before", session.createSelectionQuery("select label from Item where id=2", String.class).getSingleResult());
            assertEquals(1, session.createNamedQuery("hqlUpdate").setParameter("id", 2L).setParameter("label", "legacy").executeUpdate());
            assertEquals("legacy", session.createSelectionQuery("select label from Item where id=2", String.class).getSingleResult());
            assertEquals(1, session.createNamedStatement("hqlDelete").setParameter("id", 1L).executeUpdate());
            assertEquals(1, session.createNamedQuery("hqlDelete").setParameter("id", 2L).executeUpdate());
            assertEquals(0L, session.createSelectionQuery("select count(*) from Item", Long.class).getSingleResult());
            tx.rollback();
        }
    }

    @ParameterizedTest @MethodSource("variants")
    void nativeStatementsAndLegacyCallers(FixtureBundle.Entry entry) throws Exception {
        boot(entry);
        try (Session session = factory.openSession()) {
            var tx = session.beginTransaction();
            assertEquals(1, session.createNamedStatement("sqlInsert").setParameter("id", 3L).setParameter("label", "before").executeUpdate());
            assertEquals(1, session.createNamedStatement("sqlUpdate").setParameter("id", 3L).setParameter("label", "after").executeUpdate());
            assertEquals("after", session.createSelectionQuery("select label from Item where id=3", String.class).getSingleResult());
            assertEquals(1, session.createNamedQuery("sqlUpdate").setParameter("id", 3L).setParameter("label", "legacy").executeUpdate());
            assertEquals("legacy", session.createSelectionQuery("select label from Item where id=3", String.class).getSingleResult());
            assertEquals(1, session.createNamedStatement("sqlDelete").setParameter("id", 3L).executeUpdate());
            assertEquals(1, session.createNamedQuery("sqlInsert").setParameter("id", 4L).setParameter("label", "legacy").executeUpdate());
            assertEquals(1, session.createNamedQuery("sqlDelete").setParameter("id", 4L).executeUpdate());
            assertEquals(0L, session.createSelectionQuery("select count(*) from Item", Long.class).getSingleResult());
            tx.rollback();
        }
    }

    @ParameterizedTest @MethodSource("variants")
    void delegateIdentityAndClosedBehavior(FixtureBundle.Entry entry) throws Exception {
        boot(entry);
        Method migrated = fixture.getMethod("delegate", EntityManager.class);
        Session session = factory.openSession();
        assertSame(session, session.getDelegate());
        assertSame(session.getDelegate(), migrated.invoke(null, session));
        session.close();
        Throwable original = assertThrows(RuntimeException.class, session::getDelegate);
        Throwable transformed = assertThrows(InvocationTargetException.class, () -> migrated.invoke(null, session)).getCause();
        assertEquals(original.getClass(), transformed.getClass());
    }

    @ParameterizedTest @MethodSource("variants")
    void mapKeyUsesProperty(FixtureBundle.Entry entry) throws Exception {
        boot(entry);
        Object owner = department.getConstructor().newInstance();
        Object value = item.getConstructor().newInstance();
        department.getField("id").set(owner, 10L);
        item.getField("id").set(value, 11L);
        item.getField("label").set(value, "property-key");
        @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) department.getField("items").get(owner);
        map.put("property-key", value);
        try (Session session = factory.openSession()) {
            var tx = session.beginTransaction();
            session.persist(owner);
            session.flush(); session.clear();
            Object loaded = session.find(department, 10L);
            Map<?, ?> loadedMap = (Map<?, ?>) department.getField("items").get(loaded);
            assertEquals(Set.of("property-key"), loadedMap.keySet());
            assertEquals(11L, item.getField("id").get(loadedMap.get("property-key")));
            tx.rollback();
        }
    }
}
