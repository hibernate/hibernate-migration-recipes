plugins {
    id("java")
    id("org.hibernate.migration-testing")
}

group = "org.hibernate.migration"
version = "1.0-SNAPSHOT"
repositories { mavenCentral() }

val rewriteBomVersion = "3.37.0"
val junitVersion = "6.1.3"

dependencies {
    implementation(platform("org.openrewrite.recipe:rewrite-recipe-bom:$rewriteBomVersion"))
    implementation("org.openrewrite:rewrite-java")
    implementation("org.openrewrite:rewrite-xml")
    implementation("org.openrewrite:rewrite-groovy")
    implementation("org.openrewrite:rewrite-kotlin")
    implementation("org.openrewrite:rewrite-maven")
    implementation("org.openrewrite:rewrite-toml")
    runtimeOnly("org.openrewrite:rewrite-java-17")
    runtimeOnly("org.openrewrite:rewrite-java-21")
    testImplementation("org.openrewrite:rewrite-test")
    testImplementation(platform("org.junit:junit-bom:$junitVersion"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

migrationTesting {
    junitVersion.set("6.1.3")
    environments {
        register("orm74") {
            ormVersion.set("7.4.11.Final")
        }
        register("orm80") {
            ormVersion.set("8.0.0.Beta2")
        }
    }
    migrations {
        register("orm80") {
            sourceEnvironment.set("orm74")
            targetEnvironment.set("orm80")
            databaseDependency.set("com.h2database:h2:2.5.252")
        }
    }
}

val orm80TargetVersion = providers.provider {
    val migration = migrationTesting.migrations.getByName("orm80")
    migrationTesting.environments.getByName(migration.targetEnvironment.get()).ormVersion.get().also {
        require(it.matches(Regex("8\\.0\\.[0-9]+\\.(Final|(?:Alpha|Beta|CR)[0-9]+)"))) {
            "The orm80 aggregate requires an exact ORM 8.0 release: $it"
        }
    }
}
tasks.processResources {
    inputs.property("orm80TargetVersion", orm80TargetVersion)
    filesMatching("META-INF/rewrite/orm80.yml") {
        filter { line -> line.replace("@orm80TargetVersion@", orm80TargetVersion.get()) }
    }
    doLast {
        require(!destinationDir.resolve("META-INF/rewrite/orm80.yml").readText().contains("@orm80TargetVersion@"))
    }
}

tasks.named<JavaCompile>("compileJava") {
    options.release.set(17)
    // OpenRewrite binds configured recipe constructor arguments by their Java parameter names.
    options.compilerArgs.add("-parameters")
}
sourceSets.test {
    java.srcDir("src/testSupport/java")
    resources.srcDir("src/testSupport/resources")
}
tasks.test {
    useJUnitPlatform()
}

val verifyBuildConvention = tasks.register<GradleBuild>("verifyBuildConvention") {
    group = "verification"
    buildName = "migration-testing-convention-tests"
    dir = file("buildSrc")
    tasks = listOf("test")
}
tasks.check { dependsOn(verifyBuildConvention) }

// Resolver tools are isolated from production recipes and from the Rewrite runtime.
val coordinateAntTools = configurations.create("coordinateAntTools")
val coordinateMavenTool = configurations.create("coordinateMavenTool")
dependencies {
    add(coordinateAntTools.name, "org.apache.ant:ant:1.10.18")
    add(coordinateAntTools.name, "org.apache.ivy:ivy:2.5.3")
    add(coordinateAntTools.name, "org.apache.maven:maven-ant-tasks:2.1.3")
    add(coordinateMavenTool.name, "org.apache.maven:apache-maven:3.9.11:bin@zip")
}
val prepareCoordinateMaven = tasks.register<Sync>("prepareCoordinateMaven") {
    from(coordinateMavenTool.map { zipTree(it) })
    into(layout.buildDirectory.dir("coordinate-tools/maven"))
}
afterEvaluate {
    val fixtureDirectory = layout.buildDirectory.dir("converted-build-fixtures/orm80")
    val executionDirectory = layout.buildDirectory.dir("build-tool-tests/orm80")
    val conversionSuite = sourceSets.getByName("conversionTestOrm80")
    val generateCoordinateBuildFixtures = tasks.register<JavaExec>("generateCoordinateBuildFixtures") {
        group = "verification"
        description = "Generates actual migrated build files for resolver verification."
        classpath = sourceSets.getByName("conversionTestOrm80").runtimeClasspath
        mainClass.set("org.hibernate.migration.recipes.orm80.OrmCoordinateBuildFixtures")
        dependsOn("conversionTestOrm80Classes", "verifyMigrationEnvironments")
        inputs.file(layout.buildDirectory.file("migration-testing/environments.properties"))
        outputs.dir(fixtureDirectory)
        systemProperty("migration.metadata", layout.buildDirectory.file("migration-testing/environments.properties").get().asFile.absolutePath)
        systemProperty("coordinate.fixtures", fixtureDirectory.get().asFile.absolutePath)
        doFirst { delete(fixtureDirectory) }
    }
    tasks.named<Test>("conversionTestOrm80") {
        exclude("**/OrmCoordinateResolutionTest.class", "**/OrmCoordinateResourceTest.class")
    }
    val buildToolTestOrm80 = tasks.register<Test>("buildToolTestOrm80") {
        group = "verification"
        description = "Verifies migrated ORM build files with Gradle, Maven, Ivy, and Ant."
        testClassesDirs = conversionSuite.output.classesDirs
        classpath = conversionSuite.runtimeClasspath
        include("**/OrmCoordinateResolutionTest.class", "**/OrmCoordinateResourceTest.class")
        useJUnitPlatform()
        maxHeapSize = "1g"
        dependsOn(generateCoordinateBuildFixtures, prepareCoordinateMaven)
        inputs.dir(fixtureDirectory)
        inputs.files(coordinateAntTools, coordinateMavenTool)
        inputs.file(layout.buildDirectory.file("migration-testing/environments.properties"))
        // The resource test creates a separate project from these actual build inputs.
        inputs.files("build.gradle.kts", "settings.gradle.kts", "buildSrc/build.gradle.kts",
            "buildSrc/src/main/kotlin/MigrationTestingPlugin.kt", "src/main/resources/META-INF/rewrite/orm80.yml")
        localState.register(executionDirectory)
        systemProperty("migration.metadata", layout.buildDirectory.file("migration-testing/environments.properties").get().asFile.absolutePath)
        systemProperty("coordinate.fixtures", fixtureDirectory.get().asFile.absolutePath)
        systemProperty("coordinate.workDirectory", executionDirectory.get().asFile.absolutePath)
        systemProperty("coordinate.antClasspath", coordinateAntTools.asPath)
        systemProperty("coordinate.mavenHome", layout.buildDirectory.dir("coordinate-tools/maven/apache-maven-3.9.11").get().asFile.absolutePath)
        systemProperty("coordinate.gradleExecutable", gradle.gradleHomeDir!!.resolve("bin/gradle").absolutePath)
    }
    tasks.check { dependsOn(buildToolTestOrm80) }
}
