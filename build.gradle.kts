plugins {
    id("java")
}

group = "org.hibernate.orm"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // import Rewrite's bill of materials.
    implementation(platform("org.openrewrite.recipe:rewrite-recipe-bom:latest.release"))

    // rewrite-java dependencies only necessary for Java Recipe development
    implementation("org.openrewrite:rewrite-java")

    // You only need the version that corresponds to your current
    // Java version. It is fine to add all of them, though, as
    // they can coexist on a classpath.
    runtimeOnly("org.openrewrite:rewrite-java-8")
    runtimeOnly("org.openrewrite:rewrite-java-11")
    runtimeOnly("org.openrewrite:rewrite-java-17")

    // rewrite-maven dependency only necessary for Maven Recipe development
    implementation("org.openrewrite:rewrite-maven")

    // rewrite-yaml dependency only necessary for Yaml Recipe development
    implementation("org.openrewrite:rewrite-yaml")

    // rewrite-properties dependency only necessary for Properties Recipe development
    implementation("org.openrewrite:rewrite-properties")

    // rewrite-xml dependency only necessary for XML Recipe development
    implementation("org.openrewrite:rewrite-xml")

    // lombok is optional, but recommended for authoring recipes
    compileOnly("org.projectlombok:lombok:latest.release")
    annotationProcessor("org.projectlombok:lombok:latest.release")

    // For authoring tests for any kind of Recipe
    testImplementation("org.openrewrite:rewrite-test")
    testImplementation("org.junit.jupiter:junit-jupiter-api:latest.release")
    testImplementation("org.junit.jupiter:junit-jupiter-params:latest.release")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:latest.release")
}

tasks.named<JavaCompile>("compileJava") {
    options.release.set(8)
}