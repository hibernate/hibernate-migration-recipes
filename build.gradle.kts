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
            javaVersion.set(21)
        }
        register("orm80") {
            ormVersion.set("8.0.0.Beta3")
            javaVersion.set(21)
        }
    }
    migrations {
        register("orm80") {
            sourceEnvironment.set("orm74")
            targetEnvironment.set("orm80")
            databaseDependency.set("com.h2database:h2:2.4.240")
        }
    }
    supplementaryApis {
        register("jpa30") { dependency.set("jakarta.persistence:jakarta.persistence-api:3.0.0") }
        register("jpa31") { dependency.set("jakarta.persistence:jakarta.persistence-api:3.1.0") }
        register("jpa32") { dependency.set("jakarta.persistence:jakarta.persistence-api:3.2.0") }
    }
}

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
tasks.named<JavaCompile>("compileJava") { options.release.set(8) }
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
