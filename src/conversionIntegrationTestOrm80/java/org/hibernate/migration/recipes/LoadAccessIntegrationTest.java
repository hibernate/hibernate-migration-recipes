package org.hibernate.migration.recipes;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import javax.tools.ToolProvider;
import org.hibernate.migration.testing.FixtureBundle;
import org.hibernate.migration.testing.ValidationEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/// Executes original 7.4 and generated Beta2 fixtures in isolated provider classloaders.
/// @author Steve Ebersole
class LoadAccessIntegrationTest {
    @TempDir Path temporary;

    @Test
    void loadingSemanticsMatchThePublishedSourceEnvironment() throws Exception {
        var environments = ValidationEnvironment.configured();
        var context = environments.primary();
        Path root = Path.of(System.getProperty("transformedFixtures"));
        var entry = FixtureBundle.validate(root, context.migration(), context.target(),
                FixtureBundle.catalog(context.migration()), environments).stream()
                .filter(e -> e.fixture().equals("loadAccess")).findFirst().orElseThrow();
        Path output = root.resolve(entry.id()).resolve("sources/fixture/loadaccess/RuntimeLoads.java");
        Path original = Path.of("src/migrationSourcesOrm80/java/fixture/loadaccess/RuntimeLoads.java").toAbsolutePath();
        assertEquals(execute(original, context.input(), environments, "RuntimeLoads"), execute(output, context.target(), environments, "RuntimeLoads"));
    }

    @Test
    void retainedReferencesExposeTheBeta2ContractDifferences() throws Exception {
        var environments = ValidationEnvironment.configured();
        var context = environments.primary();
        Path root = Path.of(System.getProperty("transformedFixtures"));
        var entry = FixtureBundle.validate(root, context.migration(), context.target(),
                FixtureBundle.catalog(context.migration()), environments).stream()
                .filter(e -> e.fixture().equals("loadAccessReferences")).findFirst().orElseThrow();
        Object result = execute(root.resolve(entry.id()).resolve("sources/fixture/loadaccess/ReferenceContracts.java"),
                context.target(), environments, "ReferenceContracts");
        assertEquals(java.util.Map.of("referenceContracts", true), result);
    }

    private Object execute(Path source, String environment, ValidationEnvironment environments, String fixture) throws Exception {
        Path classes = Files.createDirectory(temporary.resolve(environment + "-" + fixture));
        String compilePath = String.join(File.pathSeparator, environments.paths(environment).stream().map(Path::toString).toList());
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "--release",
                environments.value(environment + ".javaVersion"), "-classpath", compilePath,
                "-d", classes.toString(), source.toString()));
        var urls = new ArrayList<URL>();
        urls.add(classes.toUri().toURL());
        for (String path : environments.value(environment + ".runtime").split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            urls.add(Path.of(path).toUri().toURL());
        }
        urls.add(Class.forName("org.h2.Driver").getProtectionDomain().getCodeSource().getLocation());
        try (var loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            try {
                Thread.currentThread().setContextClassLoader(loader);
                return loader.loadClass("fixture.loadaccess." + fixture).getMethod("verify").invoke(null);
            }
            catch (java.lang.reflect.InvocationTargetException failure) {
                throw new AssertionError("Runtime contract in " + environment, failure.getCause());
            }
            finally { Thread.currentThread().setContextClassLoader(previous); }
        }
    }
}
