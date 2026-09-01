plugins {
    id("java")
}

group = "org.hibernate.orm"
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
    testRuntimeOnly("org.openrewrite:rewrite-java-21")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

tasks.named<JavaCompile>("compileJava") {
    options.release.set(8)
}
