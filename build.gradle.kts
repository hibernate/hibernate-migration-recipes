plugins {
    id("java")
}

group = "org.hibernate.migration"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("org.openrewrite.recipe:rewrite-recipe-bom:3.37.0"))

    implementation("org.openrewrite:rewrite-java")
    implementation("org.openrewrite:rewrite-xml")

    testImplementation("org.openrewrite:rewrite-test")
    testImplementation("org.junit.jupiter:junit-jupiter-api:latest.release")
    testImplementation("org.junit.jupiter:junit-jupiter-params:latest.release")
    testImplementation("jakarta.persistence:jakarta.persistence-api:3.2.0")

    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:latest.release")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:latest.release")
    // OpenRewrite selects the right parser at runtime based on the JDK running the build.
    // rewrite-java-25 exists but is not published to Maven Central; it requires an authenticated
    // Moderne repository: https://quarkusio.zulipchat.com/#narrow/channel/187038-dev/topic/Moderne.20Source.20Available.20License/near/625964139
    runtimeOnly("org.openrewrite:rewrite-java-17")
    runtimeOnly("org.openrewrite:rewrite-java-21")
}

// JDK 21 max: rewrite-java-25 is not on Maven Central (see above).
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

tasks.named<JavaCompile>("compileJava") {
    options.release.set(8)
}
