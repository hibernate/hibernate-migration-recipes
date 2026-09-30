/* SPDX-License-Identifier: Apache-2.0 */
package org.hibernate.migration.recipes;

import org.hibernate.cfg.Configuration;
import org.hibernate.migration.testing.FixtureBundle;
import org.hibernate.migration.testing.ValidationEnvironment;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.time.*;
import java.util.Date;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/// Executes emitted temporal conversions and persists their values through the target ORM.
/// Each variant compiles and loads its generated fixture once, then checks independent value
/// contracts with fresh entities and reset counters before verifying persistence.
/// @author Steve Ebersole
class TemporalConversionIntegrationTestOrm80 {
    private static final Instant INITIAL_INSTANT = Instant.parse("2024-03-10T09:30:00Z");
    private static final ZoneId CONFIGURED_ZONE = ZoneId.of("Europe/Paris");
    private static final ZoneId CALENDAR_ZONE = ZoneId.of("America/Denver");

    @TempDir Path classes;

    static Stream<FixtureBundle.Entry> variants() throws Exception {
        var environments = ValidationEnvironment.configured();
        var context = environments.primary();
        return FixtureBundle.validate(Path.of(System.getProperty("transformedFixtures")),
                context.migration(), context.target(), FixtureBundle.catalog(context.migration()), environments)
                .stream().filter(entry -> entry.scenario().startsWith("temporal"));
    }

    @ParameterizedTest(name = "values and persistence: {0}")
    @MethodSource("variants")
    void valuesAndPersistence(FixtureBundle.Entry entry) throws Exception {
        compileFixture(entry);
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            var fixture = new FixtureAccess(loader.loadClass("fixture.TemporalEntity"), entry.scenario());
            verifyInitializedValues(fixture);
            verifyNullConversion(fixture);
            verifySingleEvaluation(fixture);
            verifyReceiverOrderAndAccessors(fixture);
            verifyTimestampPrecision(fixture);
            verifyPersistence(fixture, entry, loader);
        }
    }

    private void compileFixture(FixtureBundle.Entry entry) {
        var environments = ValidationEnvironment.configured();
        String target = environments.primary().target();
        assertEquals(environments.value(target + ".ormVersion"), org.hibernate.Version.getVersionString(), "Target ORM identity");
        Path source = Path.of(System.getProperty("transformedFixtures"), entry.id(), "sources/fixture/TemporalEntity.java");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none",
                "--release", environments.value(target + ".javaVersion"), "-classpath", System.getProperty("java.class.path"),
                "-d", classes.toString(), source.toString()), entry.scenario() + ": compile " + source);
    }

    private void verifyInitializedValues(FixtureAccess fixture) {
        Object entity = fixture.newEntity();
        assertEquals(LocalDate.of(2024, 3, 10), fixture.field(entity, "localDay"), fixture.label("localDay initializer"));
        assertEquals(LocalTime.of(10, 30), fixture.field(entity, "localTime"), fixture.label("localTime initializer"));
        assertEquals(expected(fixture.scenario, INITIAL_INSTANT, CONFIGURED_ZONE, false), fixture.field(entity, "stamp"), fixture.label("stamp initializer"));
        assertEquals(expected(fixture.scenario, INITIAL_INSTANT, CALENDAR_ZONE, true), fixture.field(entity, "zoned"), fixture.label("Calendar initializer"));
        Object details = fixture.field(entity, "details");
        assertEquals(expected(fixture.scenario, INITIAL_INSTANT, CONFIGURED_ZONE, false),
                fixture.call(details, "getObserved"), fixture.label("embedded property initializer"));
    }

    private void verifyNullConversion(FixtureAccess fixture) {
        Object entity = fixture.newEntity();
        assertNull(fixture.field(entity, "nullable"), fixture.label("null initializer"));
        fixture.assignDate(entity, null);
        assertNull(fixture.field(entity, "nullable"), fixture.label("null assignment"));
    }

    private void verifySingleEvaluation(FixtureAccess fixture) {
        Object entity = fixture.newEntity();
        fixture.resetCounters();
        fixture.call(entity, "assignOnce");
        assertEquals(1, fixture.field(null, "calls"), fixture.label("legacy expression evaluated once"));
        assertEquals(expected(fixture.scenario, INITIAL_INSTANT, CONFIGURED_ZONE, false),
                fixture.field(entity, "nullable"), fixture.label("single-evaluation result"));
    }

    private void verifyReceiverOrderAndAccessors(FixtureAccess fixture) {
        Object entity = fixture.newEntity();
        fixture.resetCounters();
        fixture.call(entity, "assignThroughAccessor");
        assertEquals("RA", fixture.field(null, "evaluationOrder"), fixture.label("receiver before argument"));
        assertEquals(1, fixture.field(null, "calls"), fixture.label("accessor argument evaluated once"));
        assertEquals(fixture.field(entity, "nullable"), fixture.call(entity, "getNullable"), fixture.label("getter and field agree"));
    }

    private void verifyTimestampPrecision(FixtureAccess fixture) {
        Object entity = fixture.newEntity();
        // A Date-typed argument carries Timestamp nanos through a regional DST overlap.
        Instant overlap = Instant.parse("2024-10-27T01:30:00.123456789Z");
        fixture.assignDate(entity, java.sql.Timestamp.from(overlap));
        assertEquals(expected(fixture.scenario, overlap, CONFIGURED_ZONE, false),
                fixture.field(entity, "nullable"), fixture.label("Timestamp nanoseconds at DST overlap"));
    }

    private void verifyPersistence(FixtureAccess fixture, FixtureBundle.Entry entry, ClassLoader loader) {
        Object entity = fixture.newEntity();
        fixture.assignDate(entity, new Date(INITIAL_INSTANT.toEpochMilli()));
        Object details = fixture.field(entity, "details");
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(loader);
            try (var factory = new Configuration().addAnnotatedClass(fixture.type)
                    .setProperty("hibernate.connection.url", "jdbc:h2:mem:" + entry.fixture() + ";DB_CLOSE_DELAY=-1")
                    .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                    .setProperty("hibernate.jdbc.time_zone", "UTC")
                    .buildSessionFactory()) {
                fixture.setField(entity, "id", 1L);
                factory.inTransaction(session -> session.persist(entity));
                factory.inTransaction(session -> {
                    Object loaded = session.find(fixture.type, 1L);
                    for (String property : new String[]{"localDay", "localTime", "stamp", "zoned"}) {
                        assertStoredEquivalent(fixture.field(entity, property), fixture.field(loaded, property),
                                fixture.label(property + " persistence"));
                    }
                    Object loadedDetails = fixture.field(loaded, "details");
                    assertStoredEquivalent(fixture.call(details, "getObserved"), fixture.call(loadedDetails, "getObserved"),
                            fixture.label("embedded property persistence"));
                });
            }
        }
        finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private static Object expected(String scenario, Instant instant, ZoneId zone, boolean calendar) {
        return switch (scenario) {
            case "temporalInstant" -> instant;
            case "temporalLocal" -> instant.atZone(zone).toLocalDateTime();
            case "temporalOffset" -> instant.atZone(calendar ? zone : ZoneOffset.ofHours(2)).toOffsetDateTime();
            case "temporalZoned" -> instant.atZone(zone);
            default -> throw new IllegalArgumentException(scenario);
        };
    }

    private static void assertStoredEquivalent(Object expected, Object actual, String context) {
        // Default Hibernate storage may normalize zone/offset identity; require the same instant.
        if (expected instanceof ZonedDateTime zoned) assertEquals(zoned.toInstant(), ((ZonedDateTime) actual).toInstant(), context);
        else if (expected instanceof OffsetDateTime offset) assertEquals(offset.toInstant(), ((OffsetDateTime) actual).toInstant(), context);
        else assertEquals(expected, actual, context);
    }

    /// Access to generated fixture members, without conversion policy or expected-value logic.
    private static final class FixtureAccess {
        private final Class<?> type;
        private final String scenario;

        private FixtureAccess(Class<?> type, String scenario) {
            this.type = type;
            this.scenario = scenario;
        }

        private String label(String contract) { return scenario + ": " + contract; }

        private Object newEntity() {
            try { return type.getConstructor().newInstance(); }
            catch (ReflectiveOperationException ex) { throw failure("TemporalEntity constructor", ex); }
        }

        private Object field(Object receiver, String name) {
            try { return (receiver == null ? type : receiver.getClass()).getField(name).get(receiver); }
            catch (ReflectiveOperationException ex) { throw failure(name, ex); }
        }

        private void setField(Object receiver, String name, Object value) {
            try { (receiver == null ? type : receiver.getClass()).getField(name).set(receiver, value); }
            catch (ReflectiveOperationException ex) { throw failure(name, ex); }
        }

        private Object call(Object receiver, String method) {
            try { return receiver.getClass().getMethod(method).invoke(receiver); }
            catch (ReflectiveOperationException ex) { throw failure(method, ex); }
        }

        private void assignDate(Object receiver, Date value) {
            try { type.getMethod("assign", Date.class).invoke(receiver, new Object[]{value}); }
            catch (ReflectiveOperationException ex) { throw failure("assign(Date)", ex); }
        }

        private void resetCounters() {
            setField(null, "calls", 0);
            setField(null, "evaluationOrder", "");
        }

        private AssertionError failure(String member, ReflectiveOperationException cause) {
            return new AssertionError(label("accessing " + member), cause);
        }
    }
}
