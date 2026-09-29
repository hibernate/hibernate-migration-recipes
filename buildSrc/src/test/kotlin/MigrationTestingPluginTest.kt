import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files

class MigrationTestingPluginTest {
    private fun project(): ProjectInternal = (ProjectBuilder.builder().withProjectDir(Files.createTempDirectory("migration-build-test").toFile()).build() as ProjectInternal).also {
        it.pluginManager.apply(MigrationTestingPlugin::class.java)
    }
    private fun configure(p: ProjectInternal) {
        val model = p.extensions.getByType(MigrationTestingExtension::class.java)
        listOf("old", "middle", "next").forEach { id ->
            model.environments.create(id).apply {
                ormVersion.set("1.0.0.Final")
            }
        }
        model.migrations.create("orm80").apply {
            sourceEnvironment.set("old"); targetEnvironment.set("middle"); databaseDependency.set("example:database:1")
        }
        model.migrations.create("orm9").apply {
            sourceEnvironment.set("middle"); targetEnvironment.set("next"); databaseDependency.set("example:database:1")
        }
    }
    @Test fun `two migrations reuse an environment without resolving dependencies`() {
        val p = project(); configure(p); p.evaluate()
        assertFalse(p.state.failure != null, p.state.failure?.toString())
        val sets = p.extensions.getByType(JavaPluginExtension::class.java).sourceSets
        assertEquals(setOf("main", "test", "conversionTestOrm80", "conversionIntegrationTestOrm80", "conversionTestOrm9", "conversionIntegrationTestOrm9"), sets.names)
        assertEquals(6, p.configurations.count { it.name.startsWith("ormEnvironment") })
        for (id in listOf("Orm80", "Orm9")) {
            val integration = p.tasks.getByName("conversionIntegrationTest$id")
            assertTrue(integration.taskDependencies.getDependencies(integration).any { it.name == "generateConvertedFixtures$id" })
            val generator = p.tasks.getByName("generateConvertedFixtures$id")
            assertFalse(generator.taskDependencies.getDependencies(generator).any { it is org.gradle.api.tasks.testing.Test })
        }
        assertTrue(p.configurations.getByName("conversionIntegrationTestOrm80Implementation").extendsFrom.isEmpty())
    }
    @Test fun `unknown environment fails without resolution`() {
        val p = project(); configure(p)
        p.extensions.getByType(MigrationTestingExtension::class.java).migrations.getByName("orm80").sourceEnvironment.set("missing")
        val failure = assertThrows(org.gradle.api.ProjectConfigurationException::class.java) { p.evaluate() }
        assertTrue(failure.cause.toString().contains("Unknown source environment"), failure.toString())
    }
    @Test fun `migration tasks and metadata use the JDK running Gradle`() {
        val p = project(); configure(p); p.evaluate()
        val current = org.gradle.api.JavaVersion.current().majorVersion.toInt()
        assertEquals(current, p.tasks.getByName("verifyMigrationEnvironments").inputs.properties["javaVersion"])
        for (id in listOf("Orm80", "Orm9")) {
            for (suite in listOf("conversionTest$id", "conversionIntegrationTest$id")) {
                val test = p.tasks.getByName(suite) as org.gradle.api.tasks.testing.Test
                assertEquals(current, test.javaLauncher.get().metadata.languageVersion.asInt())
                val compiler = p.tasks.getByName("compile${suite.replaceFirstChar { it.uppercaseChar() }}Java") as org.gradle.api.tasks.compile.JavaCompile
                assertEquals(current, compiler.javaCompiler.get().metadata.languageVersion.asInt())
            }
            val generator = p.tasks.getByName("generateConvertedFixtures$id") as org.gradle.api.tasks.JavaExec
            assertEquals(current, generator.javaLauncher.get().metadata.languageVersion.asInt())
            assertEquals(current, generator.inputs.properties["javaVersion"])
        }
    }
    @Test fun `task collisions are rejected`() {
        val p = project(); configure(p); p.tasks.register("conversionIntegrationTestOrm80")
        val failure = assertThrows(org.gradle.api.ProjectConfigurationException::class.java) { p.evaluate() }
        assertTrue(failure.cause.toString().contains("already exists"), failure.toString())
    }
    @Test fun `ambiguous identifiers are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { MigrationTestingPlugin.suffix("orm-8") }
    }
}
