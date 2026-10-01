package org.hibernate.migration.recipes;

import java.net.URLClassLoader;
import java.nio.file.Path;
import javax.tools.ToolProvider;

import org.hibernate.SessionFactory;
import org.hibernate.StaleStateException;
import org.hibernate.cfg.Configuration;
import org.hibernate.migration.testing.FixtureBundle;
import org.hibernate.migration.testing.ValidationEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/// Executes migrated custom SQL and verifies its outcome checks against ORM 8.
///
/// @author Steve Ebersole
class SqlVerificationIntegrationTestOrm80 {
	@TempDir Path classes;

	@Test
	void zeroRowsAreAcceptedRejectedOrPassedToTheExplicitExpectation() throws Exception {
		var environments = ValidationEnvironment.configured();
		var context = environments.primary();
		var entry = FixtureBundle.validate(Path.of(System.getProperty("transformedFixtures")), context.migration(), context.target(),
				FixtureBundle.catalog(context.migration()), environments).stream()
				.filter(candidate -> candidate.scenario().equals("sql-verification")).findFirst().orElseThrow();
		Path source = Path.of(System.getProperty("transformedFixtures"), entry.id(), "sources/fixture/sqlverification/RuntimeEntities.java");
		assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-classpath",
				System.getProperty("java.class.path"), "-d", classes.toString(), source.toString()));
		try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
			Class<?> none = loader.loadClass("fixture.sqlverification.RuntimeEntities$NoneRecord");
			Class<?> count = loader.loadClass("fixture.sqlverification.RuntimeEntities$CountRecord");
			Class<?> custom = loader.loadClass("fixture.sqlverification.RuntimeEntities$CustomRecord");
			Class<?> expectation = loader.loadClass("fixture.sqlverification.RuntimeEntities$Custom");
			ClassLoader previous = Thread.currentThread().getContextClassLoader();
			try {
				Thread.currentThread().setContextClassLoader(loader);
				try (SessionFactory factory = new Configuration().addAnnotatedClass(none).addAnnotatedClass(count).addAnnotatedClass(custom)
						.setProperty("hibernate.connection.url", "jdbc:h2:mem:sql_verification;DB_CLOSE_DELAY=-1")
						.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
						.setProperty("hibernate.hbm2ddl.auto", "create-drop")
						.setProperty("hibernate.jdbc.batch_size", "0")
						.buildSessionFactory()) {
					insert(factory, none);
					Throwable failure = assertThrows(RuntimeException.class, () -> insert(factory, count));
					while (!(failure instanceof StaleStateException) && failure.getCause() != null) failure = failure.getCause();
					assertInstanceOf(StaleStateException.class, failure);
					insert(factory, custom);
					assertEquals(1, expectation.getField("calls").getInt(null));
					assertEquals(0, expectation.getField("lastRowCount").getInt(null));
					try (var session = factory.openSession()) {
						for (String entity : new String[]{"NoneRecord", "CountRecord", "CustomRecord"}) {
							assertEquals(0L, session.createSelectionQuery("select count(*) from " + entity, Long.class).getSingleResult());
						}
					}
				}
			}
			finally {
				Thread.currentThread().setContextClassLoader(previous);
			}
		}
	}

	private void insert(SessionFactory factory, Class<?> entityClass) throws Exception {
		Object entity = entityClass.getConstructor().newInstance();
		entityClass.getField("id").set(entity, 1L);
		try (var session = factory.openSession()) {
			var transaction = session.beginTransaction();
			try {
				session.persist(entity);
				session.flush();
			}
			finally {
				transaction.rollback();
			}
		}
	}
}
