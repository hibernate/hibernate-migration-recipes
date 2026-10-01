package org.hibernate.migration.recipes.orm80;

import org.hibernate.migration.testing.ValidationEnvironment;
import org.openrewrite.*;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import java.nio.file.*;
import java.util.*;

/// Publishes actual recipe outputs for fresh Gradle, Maven, Ivy, and Maven Ant Tasks resolution.
///
/// @author Steve Ebersole
public final class OrmCoordinateBuildFixtures {
    private OrmCoordinateBuildFixtures() {}
    public static void main(String[] args) throws Exception {
        var environment = ValidationEnvironment.configured();
        String source = environment.value(environment.source("orm80") + ".ormVersion");
        String target = environment.value(environment.target("orm80") + ".ormVersion");
        Path root = Path.of(System.getProperty("coordinate.fixtures"));
        Map<String, String> files = new LinkedHashMap<>();
        for (String dsl : List.of("groovy", "kotlin", "catalog-groovy", "catalog-kotlin", "forced-groovy")) {
            boolean kotlin = dsl.endsWith("kotlin"), catalog = dsl.startsWith("catalog");
            String build = "plugins { id(\"java\"); id(\"org.hibernate.orm\") version \"" + source + "\" apply false }\nrepositories { mavenCentral() }\ndependencies {\n";
            if (catalog) {
                files.put(dsl + "/gradle/libs.versions.toml", "[versions]\norm = \"" + source + "\"\n[libraries]\nentity = { module = \"org.hibernate:hibernate-entitymanager\", version.ref = \"orm\" }\ncore = { module = \"org.hibernate:hibernate-core\", version.ref = \"orm\" }\nenvers = { module = \"org.hibernate:hibernate-envers\", version.ref = \"orm\" }\nprocessor = { module = \"org.hibernate:hibernate-jpamodelgen\", version.ref = \"orm\" }\nlogging = { module = \"org.jboss.logging:jboss-logging\", version = \"3.6.1.Final\" }\n[bundles]\norm = [\"entity\", \"core\", \"envers\", \"logging\"]\n");
                build += "    implementation(libs.bundles.orm)\n    annotationProcessor(libs.processor)\n";
            }
            else build += "    implementation(\"org.hibernate:hibernate-entitymanager:" + source + "\")\n    implementation(\"org.hibernate:hibernate-core:" + source + "\")\n    implementation(\"org.hibernate:hibernate-envers:" + source + "\")\n    annotationProcessor(\"org.hibernate:hibernate-jpamodelgen:" + source + "\")\n";
            build += "}\nlayout.buildDirectory.set(file(providers.gradleProperty(\"coordinate.workDirectory\").get()))\n";
            if (dsl.equals("forced-groovy")) build += "configurations.configureEach { resolutionStrategy.force(\"org.hibernate.orm:hibernate-core:" + source + "\") }\n";
            String inspect = kotlin ? "tasks.register(\"verifyCoordinates\") { doLast {\n    listOf(\"runtimeClasspath\", \"annotationProcessor\").forEach { configuration ->\n        configurations.getByName(configuration).resolvedConfiguration.resolvedArtifacts.forEach { artifact ->\n            println(\"SELECTED:\" + artifact.moduleVersion.id.toString())\n        }\n    }\n} }\n"
                    : "tasks.register('verifyCoordinates') { doLast {\n    ['runtimeClasspath', 'annotationProcessor'].each { configuration ->\n        configurations.getByName(configuration).resolvedConfiguration.resolvedArtifacts.each { artifact ->\n            println('SELECTED:' + artifact.moduleVersion.id.toString())\n        }\n    }\n} }\n";
            files.put(dsl + (kotlin ? "/build.gradle.kts" : "/build.gradle"), build + inspect);
            files.put(dsl + "/settings.gradle", "pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }\nrootProject.name = 'coordinate-fixture'\n");
        }
        files.put("maven/pom.xml", "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>coordinate-fixture</artifactId><version>1</version><dependencies>"
                + dependency("hibernate-entitymanager", source) + dependency("hibernate-core", source) + dependency("hibernate-envers", source)
                + "</dependencies><build><directory>${coordinate.workDirectory}</directory><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.14.1</version><configuration><annotationProcessorPaths><path><groupId>org.hibernate</groupId><artifactId>hibernate-jpamodelgen</artifactId><version>" + source + "</version></path></annotationProcessorPaths></configuration></plugin><plugin><groupId>org.hibernate.orm</groupId><artifactId>hibernate-maven-plugin</artifactId><version>" + source + "</version></plugin></plugins></build></project>\n");
        files.put("ivy/ivy.xml", "<ivy-module version='2.0'><info organisation='example' module='coordinate-fixture'/><dependencies defaultconf='default->default'><dependency org='org.hibernate' name='hibernate-entitymanager' rev='" + source + "'/><dependency org='org.hibernate' name='hibernate-core' rev='" + source + "'/><dependency org='org.hibernate' name='hibernate-envers' rev='" + source + "'/><dependency org='org.hibernate' name='hibernate-jpamodelgen' rev='" + source + "'/></dependencies></ivy-module>\n");
        files.put("ivy/build.xml", "<project xmlns:ivy='antlib:org.apache.ivy.ant' default='resolve'><target name='resolve'><ivy:settings file='ivysettings.xml'/><ivy:resolve file='ivy.xml'/><ivy:report todir='${coordinate.workDirectory}/report' xml='true' graph='false'/></target></project>\n");
        files.put("ivy/ivysettings.xml", "<ivysettings><settings defaultResolver='central'/><resolvers><ibiblio name='central' m2compatible='true' root='https://repo.maven.apache.org/maven2'/></resolvers></ivysettings>\n");
        files.put("ant/build.xml", "<project xmlns:m='antlib:org.apache.maven.artifact.ant' default='resolve'><target name='resolve'><m:dependencies filesetId='resolved' useScope='compile' settingsFile='settings.xml'><remoteRepository id='central' url='https://repo.maven.apache.org/maven2'/><dependency groupId='org.hibernate' artifactId='hibernate-entitymanager' version='" + source + "'/><dependency groupId='org.hibernate' artifactId='hibernate-core' version='" + source + "'/><dependency groupId='org.hibernate' artifactId='hibernate-envers' version='" + source + "'/><dependency groupId='org.hibernate' artifactId='hibernate-jpamodelgen' version='" + source + "'/></m:dependencies><pathconvert property='artifacts' refid='resolved'/><echo message='SELECTED:${artifacts}'/></target></project>\n");
        files.put("ant/settings.xml", "<settings><localRepository>" + root.resolveSibling("resolver-cache/ant").toAbsolutePath()
                + "</localRepository><mirrors><mirror><id>central-https</id><mirrorOf>*</mirrorOf><url>https://repo.maven.apache.org/maven2</url></mirror></mirrors></settings>\n");
        var ctx = new InMemoryExecutionContext(e -> { throw new IllegalStateException(e); });
        var sources = files.entrySet().stream().map(e -> OrmCoordinatesTest.parse(e.getKey(), e.getValue(), ctx)).toList();
        var run = new MigrateOrmCoordinates(target).run(new InMemoryLargeSourceSet(sources), ctx);
        for (Result result : run.getChangeset().getAllResults()) files.put(result.getAfter().getSourcePath().toString(), result.getAfter().printAll());
        Files.createDirectories(root);
        for (var file : files.entrySet()) {
            Path path = root.resolve(file.getKey()); Files.createDirectories(path.getParent()); Files.writeString(path, file.getValue());
        }
        Files.writeString(root.resolve("target-version.txt"), target);
        Files.writeString(root.resolve("source-version.txt"), source);
    }
    private static String dependency(String artifact, String version) {
        return "<dependency><groupId>org.hibernate</groupId><artifactId>" + artifact + "</artifactId><version>" + version + "</version></dependency>";
    }
}
