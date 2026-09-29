import org.gradle.kotlin.dsl.*
import org.gradle.api.*
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.attributes.*
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.*
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.Test
import java.util.Properties
import javax.inject.Inject

/**
 * A reusable Hibernate ORM environment, usable as either end of a migration.
 *
 * The persistence API version comes from the published ORM dependency graph.
 * Environment names, such as `orm74`, also identify profiles in the generated metadata.
 *
 * @author Steve Ebersole
 */
open class OrmEnvironment @Inject constructor(private val id: String, objects: ObjectFactory) : Named {
    override fun getName() = id
    /** Exact published Hibernate ORM release, for example `7.4.11.Final`. */
    val ormVersion = objects.property(String::class.java)
}

/**
 * A conversion path between two named [OrmEnvironment] definitions.
 *
 * Its name determines the generated test SourceSet and task suffix: `orm80` produces
 * `conversionTestOrm80`, `generateConvertedFixturesOrm80`, and
 * `conversionIntegrationTestOrm80`. Both suites use the JDK running Gradle.
 *
 * @author Steve Ebersole
 */
open class MigrationDefinition @Inject constructor(private val id: String, objects: ObjectFactory) : Named {
    override fun getName() = id
    /** Registered environment used to understand and validate the original code. */
    val sourceEnvironment = objects.property(String::class.java)
    /** Registered environment used to compile and execute the converted code. */
    val targetEnvironment = objects.property(String::class.java)
    /** Database dependency notation added only to the target suite's runtime. */
    val databaseDependency = objects.property(String::class.java)
    /**
     * Java entry point that validates and writes the complete converted fixture bundle.
     * Runs on the source suite's classpath, independently of JUnit test selection.
     */
    val generatorMainClass = objects.property(String::class.java)
        .convention("org.hibernate.migration.recipes.jpa4.ConvertedFixtureGenerator")
}

/**
 * An additional API profile for source-validation cases, such as an earlier JPA release.
 *
 * Its classpath is exposed through metadata without replacing dependencies in an ORM
 * environment. It cannot serve as a migration's source or target environment.
 *
 * @author Steve Ebersole
 */
open class SupplementaryApi @Inject constructor(private val id: String, objects: ObjectFactory) : Named {
    override fun getName() = id
    /** Dependency notation resolved into this profile's classpath. */
    val dependency = objects.property(String::class.java)
}

/**
 * The `migrationTesting` build DSL for reusable environments and conversion suites.
 *
 * Register environments once, then refer to their names from migration definitions.
 * Supplementary APIs provide additional parser/compiler inputs for regression cases.
 *
 * @author Steve Ebersole
 */
open class MigrationTestingExtension @Inject constructor(objects: ObjectFactory) {
    /** JUnit BOM version shared by the generated test suites. */
    val junitVersion = objects.property(String::class.java).convention("6.1.3")
    /** ORM environments available as sources and targets. */
    val environments = objects.domainObjectContainer(OrmEnvironment::class.java) { objects.newInstance(OrmEnvironment::class.java, it) }
    /** Conversion paths for which the plugin creates validation suites. */
    val migrations = objects.domainObjectContainer(MigrationDefinition::class.java) { objects.newInstance(MigrationDefinition::class.java, it) }
    /** Additional API profiles exposed to validation helpers through metadata. */
    val supplementaryApis = objects.domainObjectContainer(SupplementaryApi::class.java) { objects.newInstance(SupplementaryApi::class.java, it) }
    /** Configures the reusable ORM environment definitions. */
    fun environments(action: Action<NamedDomainObjectContainer<OrmEnvironment>>) = action.execute(environments)
    /** Configures the conversion paths and their fixture generators. */
    fun migrations(action: Action<NamedDomainObjectContainer<MigrationDefinition>>) = action.execute(migrations)
    /** Configures API profiles used alongside the primary ORM source environment. */
    fun supplementaryApis(action: Action<NamedDomainObjectContainer<SupplementaryApi>>) = action.execute(supplementaryApis)
}

/**
 * Registers source validation and target integration testing for each declared migration.
 *
 * Production recipes remain in the standard `main` SourceSet and JAR. Each migration
 * gets two test SourceSets, both including `src/testSupport`: the source suite inherits
 * recipe dependencies and adds the source ORM, while the target suite uses the target
 * ORM and database without inheriting the recipe or source environment dependencies.
 *
 * `verifyMigrationEnvironments` checks the resolved ORM and JPA versions, including
 * agreement between environment profiles and suite classpaths. It writes deterministic
 * metadata to `build/migration-testing/environments.properties` for validation helpers.
 *
 * Fixture generation runs as a separate Java process before target integration tests.
 * It owns `build/converted-fixtures/<migration>` and clears that directory before each
 * execution, so filtered source tests cannot publish a partial integration fixture set.
 * The generated suites and environment verification are dependencies of `check`.
 *
 * @author Steve Ebersole
 */
class MigrationTestingPlugin : Plugin<Project> {
    /** Applies Java support and registers suites after the build's migration DSL is configured. */
    override fun apply(project: Project) = with(project) {
        pluginManager.apply("java")
        val model = extensions.create("migrationTesting", MigrationTestingExtension::class.java)
        val java = extensions.getByType(JavaPluginExtension::class.java)
        val javaVersion = JavaVersion.current().majorVersion.toInt()
        val metadata = layout.buildDirectory.file("migration-testing/environments.properties")
        val verify = tasks.register("verifyMigrationEnvironments") {
            group = "verification"
            outputs.file(metadata)
        }
        tasks.named("check") { dependsOn(verify) }
        afterEvaluate {
            val environments = model.environments.associateBy { it.name }
            val profiles = linkedMapOf<String, Pair<Configuration, Configuration>>()
            environments.values.forEach { env ->
                suffix(env.name)
                require(env.ormVersion.isPresent) { "Missing ORM version for ${env.name}" }
                require(env.ormVersion.get().matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+\\.(Final|(?:Alpha|Beta|CR)[0-9]+)"))) { "Pin a published ORM release for ${env.name}" }
                val notation = "org.hibernate.orm:hibernate-core:${env.ormVersion.get()}"
                profiles[env.name] = configuration("ormEnvironment${suffix(env.name)}CompileClasspath", Usage.JAVA_API, notation) to
                    configuration("ormEnvironment${suffix(env.name)}RuntimeClasspath", Usage.JAVA_RUNTIME, notation)
            }
            model.supplementaryApis.forEach { api ->
                require(!profiles.containsKey(api.name)) { "Duplicate environment/profile ${api.name}" }
                val cp = configuration("supplementary${suffix(api.name)}Classpath", Usage.JAVA_RUNTIME, api.dependency.get())
                profiles[api.name] = cp to cp
            }
            val suiteChecks = mutableListOf<Triple<String, Configuration, String>>()
            val paths = model.migrations.associate { it.name to (it.sourceEnvironment.get() to it.targetEnvironment.get()) }
            model.migrations.forEach { migration ->
                val suffix = suffix(migration.name)
                val source = environments[migration.sourceEnvironment.get()]
                    ?: error("Unknown source environment ${migration.sourceEnvironment.get()} for ${migration.name}")
                val target = environments[migration.targetEnvironment.get()]
                    ?: error("Unknown target environment ${migration.targetEnvironment.get()} for ${migration.name}")
                require(source.name != target.name) { "Source and target environments must differ for ${migration.name}" }
                val sourceName = "conversionTest$suffix"
                val targetName = "conversionIntegrationTest$suffix"
                listOf(sourceName, targetName, "generateConvertedFixtures$suffix").forEach {
                    require(tasks.findByName(it) == null) { "Generated task name already exists: $it" }
                }
                val sourceSet = java.sourceSets.create(sourceName)
                val targetSet = java.sourceSets.create(targetName)
                listOf(sourceSet, targetSet).forEach { suite ->
                    suite.java.srcDir("src/testSupport/java")
                    suite.resources.srcDir("src/testSupport/resources")
                    dependencies.add(suite.implementationConfigurationName, dependencies.platform("org.junit:junit-bom:${model.junitVersion.get()}"))
                    dependencies.add(suite.implementationConfigurationName, "org.junit.jupiter:junit-jupiter")
                    dependencies.add(suite.runtimeOnlyConfigurationName, "org.junit.platform:junit-platform-launcher")
                }
                val main = java.sourceSets.getByName("main")
                sourceSet.compileClasspath += main.output
                sourceSet.runtimeClasspath += main.output
                configurations.getByName(sourceSet.implementationConfigurationName).extendsFrom(configurations.getByName("implementation"))
                configurations.getByName(sourceSet.runtimeOnlyConfigurationName).extendsFrom(configurations.getByName("runtimeOnly"))
                dependencies.add(sourceSet.implementationConfigurationName, "org.openrewrite:rewrite-test")
                dependencies.add(sourceSet.implementationConfigurationName, "org.hibernate.orm:hibernate-core:${source.ormVersion.get()}")
                dependencies.add(targetSet.implementationConfigurationName, "org.hibernate.orm:hibernate-core:${target.ormVersion.get()}")
                dependencies.add(targetSet.runtimeOnlyConfigurationName, migration.databaseDependency.get())
                listOf(sourceSet to source, targetSet to target).forEach { (suite, env) ->
                    suiteChecks.add(Triple(suite.name, configurations.getByName(suite.compileClasspathConfigurationName), env.name))
                    suiteChecks.add(Triple(suite.name, configurations.getByName(suite.runtimeClasspathConfigurationName), env.name))
                }
                val output = layout.buildDirectory.dir("converted-fixtures/${migration.name}")
                val recipeJar = tasks.named("jar", Jar::class.java)
                val sourceTest = tasks.register(sourceName, Test::class.java) {
                    group = "verification"
                    description = "Validates ${source.name} to ${target.name} recipe conversions."
                    testClassesDirs = sourceSet.output.classesDirs
                    classpath = sourceSet.runtimeClasspath
                    useJUnitPlatform()
                    maxHeapSize = "1g"
                    dependsOn(verify, recipeJar)
                    inputs.file(recipeJar.flatMap { it.archiveFile })
                    systemProperty("recipeJar", recipeJar.get().archiveFile.get().asFile.absolutePath)
                    inputs.file(metadata)
                    inputs.files(profiles.values.flatMap { listOf(it.first, it.second) })
                    systemProperty("migration.metadata", metadata.get().asFile.absolutePath)
                    systemProperty("migration.id", migration.name)
                }
                val generator = tasks.register("generateConvertedFixtures$suffix", JavaExec::class.java) {
                    group = "verification"
                    description = "Generates the complete validated fixture set for ${migration.name}."
                    classpath = sourceSet.runtimeClasspath
                    mainClass.set(migration.generatorMainClass)
                    maxHeapSize = "1g"
                    dependsOn(verify)
                    inputs.file(metadata)
                    inputs.files(profiles.values.flatMap { listOf(it.first, it.second) })
                    inputs.property("migration", migration.name)
                    inputs.property("javaVersion", javaVersion)
                    outputs.dir(output)
                    systemProperty("migration.metadata", metadata.get().asFile.absolutePath)
                    systemProperty("migration.id", migration.name)
                    systemProperty("transformedFixtures", output.get().asFile.absolutePath)
                    // JavaExec is not build-cache enabled. Its normal input/output snapshot provides up-to-date checking.
                    doFirst { delete(output) }
                }
                val integration = tasks.register(targetName, Test::class.java) {
                    group = "verification"
                    description = "Executes actual converted fixtures against ${target.name}."
                    testClassesDirs = targetSet.output.classesDirs
                    classpath = targetSet.runtimeClasspath
                    useJUnitPlatform()
                    maxHeapSize = "1g"
                    dependsOn(generator, verify)
                    inputs.dir(output)
                    inputs.file(metadata)
                    systemProperty("migration.metadata", metadata.get().asFile.absolutePath)
                    systemProperty("migration.id", migration.name)
                    systemProperty("transformedFixtures", output.get().asFile.absolutePath)
                }
                tasks.named("check") { dependsOn(sourceTest, integration) }
            }
            verify.configure {
                inputs.files(profiles.values.flatMap { listOf(it.first, it.second) })
                inputs.files(suiteChecks.map { it.second })
                inputs.property("environments", environments.mapValues { it.value.ormVersion.get() })
                inputs.property("javaVersion", javaVersion)
                inputs.property("migrations", paths.toString())
                doLast {
                    val values = Properties()
                    values["profiles"] = profiles.keys.joinToString(",")
                    profiles.forEach { (id, pair) ->
                        val core = module(pair.second, "org.hibernate.orm", "hibernate-core", environments.containsKey(id))
                        val jpa = module(pair.second, "jakarta.persistence", "jakarta.persistence-api", true)!!
                        environments[id]?.let {
                            require(core == it.ormVersion.get()) { "ORM version replacement in $id: $core" }
                            val component = pair.second.incoming.resolutionResult.allComponents.single { c ->
                                c.moduleVersion?.group == "org.hibernate.orm" && c.moduleVersion?.name == "hibernate-core"
                            }
                            val requestedJpa = component.dependencies.filterIsInstance<ResolvedDependencyResult>()
                                .mapNotNull { d -> d.requested as? ModuleComponentSelector }
                                .single { d -> d.group == "jakarta.persistence" && d.module == "jakarta.persistence-api" }.version
                            require(jpa == requestedJpa) { "JPA version replacement in $id: ORM requests $requestedJpa, resolved $jpa" }
                        }
                        values["$id.compile"] = pair.first.asPath
                        values["$id.runtime"] = pair.second.asPath
                        values["$id.jpaVersion"] = jpa
                        values["$id.javaVersion"] = javaVersion.toString()
                        if (core != null) {
                            values["$id.ormVersion"] = core
                            values["$id.ormJar"] = pair.second.resolvedConfiguration.resolvedArtifacts.single { it.moduleVersion.id.group == "org.hibernate.orm" && it.name == "hibernate-core" }.file.absolutePath
                        }
                        values["$id.modules"] = pair.second.resolvedConfiguration.resolvedArtifacts.map { it.moduleVersion.id.toString() }.sorted().joinToString(",")
                        require(module(pair.first, "jakarta.persistence", "jakarta.persistence-api", true) == jpa) { "Compile/runtime JPA mismatch in $id" }
                    }
                    suiteChecks.forEach { (suite, cp, env) ->
                        for ((group, artifact, property) in listOf(Triple("org.hibernate.orm", "hibernate-core", "ormVersion"), Triple("jakarta.persistence", "jakarta.persistence-api", "jpaVersion"))) {
                            require(module(cp, group, artifact, true) == values["$env.$property"]) { "$suite replaces $group:$artifact from $env" }
                        }
                    }
                    paths.forEach { (id, path) ->
                        values["migration.$id.source"] = path.first
                        values["migration.$id.target"] = path.second
                    }
                    val file = metadata.get().asFile
                    file.parentFile.mkdirs()
                    // Deterministic properties: no timestamp and escaped Windows paths/separators.
                    file.writeText(values.stringPropertyNames().sorted().joinToString("\n", postfix = "\n") {
                        "$it=${values.getProperty(it).replace("\\", "\\\\").replace(":", "\\:").replace("=", "\\=")}"
                    })
                }
            }
        }
    }

    /** Creates a resolvable, non-consumable Java library classpath for one dependency profile. */
    private fun Project.configuration(name: String, usage: String, dependency: String): Configuration = configurations.create(name) {
        isCanBeConsumed = false
        isCanBeResolved = true
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, usage))
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
            attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements::class.java, LibraryElements.JAR))
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling::class.java, Bundling.EXTERNAL))
        }
        dependencies.add(project.dependencies.create(dependency))
    }

    companion object {
        /**
         * Converts a lowercase profile or migration ID to its task/configuration suffix.
         *
         * @throws IllegalArgumentException if [id] does not start with a lowercase letter
         * or contains anything other than lowercase letters and digits.
         */
        fun suffix(id: String): String {
            require(id.matches(Regex("[a-z][a-z0-9]*"))) { "Invalid migration/environment identifier: $id" }
            return id.replaceFirstChar { it.uppercaseChar() }
        }
        /**
         * Resolves [configuration] and returns the selected version of a module.
         *
         * @return the sole matching version, or `null` when absent and [required] is false.
         * @throws IllegalArgumentException if the module is required but absent, or
         * multiple distinct versions match the requested coordinates.
         */
        fun module(configuration: Configuration, group: String, name: String, required: Boolean): String? {
            val modules = configuration.resolvedConfiguration.resolvedArtifacts.map { it.moduleVersion.id }
                .filter { it.group == group && it.name == name }.distinct()
            require(modules.size == 1 || !required && modules.isEmpty()) { "Expected one $group:$name in ${configuration.name}, got $modules" }
            return modules.singleOrNull()?.version
        }
    }
}
