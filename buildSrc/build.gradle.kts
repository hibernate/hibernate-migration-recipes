plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral(); gradlePluginPortal()
}

gradlePlugin {
    plugins {
        create("migrationTesting") {
            id = "org.hibernate.migration-testing"
            implementationClass = "MigrationTestingPlugin"
        }
    }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }
