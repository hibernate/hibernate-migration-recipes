package org.hibernate.migration.recipes.orm80;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.hibernate.migration.recipes.platform.AdoptHibernateBom;
import org.hibernate.migration.recipes.table.SkippedMigrations;
import org.hibernate.migration.testing.RecipeExecutionContexts;
import org.openrewrite.Result;
import org.openrewrite.internal.InMemoryLargeSourceSet;

/// Generates real recipe output for Maven and Gradle with an existing application BOM.
///
/// @author Steve Ebersole
final class HibernateBomBuildFixtures {
    private HibernateBomBuildFixtures() {}

    static void generate(Path root, String target) throws Exception {
        Path repository = root.resolve("application-repository").toAbsolutePath();
        Path pom = repository.resolve("example/application-platform/1/application-platform-1.pom");
        Files.createDirectories(pom.getParent());
        Files.writeString(pom, "<project><modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>application-platform</artifactId><version>1</version><packaging>pom</packaging><dependencyManagement><dependencies>"
                + "<dependency><groupId>org.hibernate.orm</groupId><artifactId>hibernate-core</artifactId><version>7.4.11.Final</version></dependency>"
                + "<dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter-api</artifactId><version>6.1.3</version></dependency>"
                + "</dependencies></dependencyManagement></project>");
        String uri = repository.toUri().toString();
        Map<String, String> files = new LinkedHashMap<>();
        for (String dsl : List.of("bom-groovy", "bom-kotlin")) {
            boolean kotlin = dsl.endsWith("kotlin");
            String build = "plugins { id(\"java\") }\nrepositories { maven { url = uri(\"" + uri + "\") }; mavenCentral() }\n"
                    + "dependencies {\n    implementation(platform(\"example:application-platform:1\"))\n"
                    + "    implementation(\"org.hibernate.orm:hibernate-core:" + target + "\")\n"
                    + "    implementation(\"org.junit.jupiter:junit-jupiter-api\")\n}\n"
                    + "layout.buildDirectory.set(file(providers.gradleProperty(\"coordinate.workDirectory\").get()))\n";
            build += kotlin ? "tasks.register(\"verifyCoordinates\") { doLast { configurations.getByName(\"runtimeClasspath\").resolvedConfiguration.resolvedArtifacts.forEach { println(\"SELECTED:\" + it.moduleVersion.id.toString()) } } }\n"
                    : "tasks.register('verifyCoordinates') { doLast { configurations.runtimeClasspath.resolvedConfiguration.resolvedArtifacts.each { println('SELECTED:' + it.moduleVersion.id.toString()) } } }\n";
            files.put(dsl + (kotlin ? "/build.gradle.kts" : "/build.gradle"), build);
            files.put(dsl + "/settings.gradle", "rootProject.name = 'bom-fixture'\n");
        }
        files.put("bom-maven/pom.xml", "<project><modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>bom-fixture</artifactId><version>1</version>"
                + "<repositories><repository><id>application</id><url>" + uri + "</url></repository><repository><id>central</id><url>https://repo.maven.apache.org/maven2</url></repository></repositories>"
                + "<dependencyManagement><dependencies><dependency><groupId>example</groupId><artifactId>application-platform</artifactId><version>1</version><type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement>"
                + "<dependencies><dependency><groupId>org.hibernate.orm</groupId><artifactId>hibernate-core</artifactId><version>" + target + "</version></dependency>"
                + "<dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter-api</artifactId></dependency></dependencies></project>\n");
        var ctx = RecipeExecutionContexts.standard(e -> { throw new IllegalStateException(e); });
        var sources = files.entrySet().stream().map(e -> OrmCoordinatesTest.parse(e.getKey(), e.getValue(), ctx)).toList();
        var run = new AdoptHibernateBom(target).run(new InMemoryLargeSourceSet(sources), ctx);
        if (!run.getDataTableRows(SkippedMigrations.class).isEmpty()) throw new IllegalStateException("Unexpected BOM adoption diagnostics");
        for (Result result : run.getChangeset().getAllResults()) files.put(result.getAfter().getSourcePath().toString(), result.getAfter().printAll());
        for (var entry : files.entrySet()) {
            Path output = root.resolve(entry.getKey());
            Files.createDirectories(output.getParent());
            Files.writeString(output, entry.getValue());
        }
    }
}
