package org.hibernate.migration.recipes;

import java.net.URLClassLoader;
import java.nio.file.Path;
import javax.tools.ToolProvider;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.migration.testing.FixtureBundle;
import org.hibernate.migration.testing.ValidationEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/// Verifies version changes using generated optimistic-lock migration output.
/// @author Steve Ebersole
class OptimisticLockIntegrationTest {
	@TempDir Path classes;

	@Test
	void exclusionInclusionAndOwningCollectionPreserveVersionBehavior() throws Exception {
		var environments = ValidationEnvironment.configured();
		var context = environments.primary();
		Path root = Path.of(System.getProperty("transformedFixtures"));
		var entry = FixtureBundle.validate(root, context.migration(), context.target(),
				FixtureBundle.catalog(context.migration()), environments).stream()
				.filter(e -> e.fixture().equals("optimistic")).findFirst().orElseThrow();
		Path source = root.resolve(entry.id()).resolve("sources/fixture/optimistic/RuntimeEntity.java");
		assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none",
				"--release", environments.value(context.target() + ".javaVersion"),
				"-classpath", System.getProperty("java.class.path"), "-d", classes.toString(), source.toString()));
		try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
			Class<?> entity = loader.loadClass("fixture.optimistic.RuntimeEntity");
			Class<?> child = loader.loadClass("fixture.optimistic.RuntimeEntity$Child");
			ClassLoader previous = Thread.currentThread().getContextClassLoader();
			SessionFactory factory;
			try {
				Thread.currentThread().setContextClassLoader(loader);
				factory = new Configuration().addAnnotatedClass(entity).addAnnotatedClass(child)
						.setProperty("hibernate.connection.url", "jdbc:h2:mem:optimistic_migration;DB_CLOSE_DELAY=-1")
						.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
						.setProperty("hibernate.hbm2ddl.auto", "create-drop")
						.buildSessionFactory();
			}
			finally {
				Thread.currentThread().setContextClassLoader(previous);
			}
			try (factory; var session = factory.openSession()) {
				var transaction = session.beginTransaction();
				Object original = entity.getConstructor().newInstance();
				entity.getField("id").set(original, 1L);
				entity.getField("excluded").set(original, "before");
				entity.getField("included").set(original, "before");
				session.persist(original);
				session.flush();
				session.clear();
				Object loaded = session.find(entity, 1L);
				int version = entity.getField("version").getInt(loaded);
				entity.getField("excluded").set(loaded, "after");
				session.flush();
				assertEquals(version, entity.getField("version").getInt(loaded));
				session.clear();
				loaded = session.find(entity, 1L);
				assertEquals("after", entity.getField("excluded").get(loaded));
				entity.getField("included").set(loaded, "after");
				session.flush();
				assertEquals(version + 1, entity.getField("version").getInt(loaded));
				Object added = child.getConstructor().newInstance();
				child.getField("id").set(added, 2L);
				@SuppressWarnings("unchecked")
				var children = (java.util.List<Object>) entity.getField("children").get(loaded);
				children.add(added);
				session.flush();
				assertEquals(version + 2, entity.getField("version").getInt(loaded));
				session.clear();
				assertEquals(1, ((java.util.List<?>) entity.getField("children").get(session.find(entity, 1L))).size());
				transaction.rollback();
			}
		}
	}
}
