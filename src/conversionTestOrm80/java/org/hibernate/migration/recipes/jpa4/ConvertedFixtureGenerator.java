package org.hibernate.migration.recipes.jpa4;

import org.hibernate.migration.testing.ApiValidation;

import org.hibernate.migration.recipes.temporal.MigrateTemporalAnnotation;
import org.hibernate.migration.recipes.temporal.TemporalRuntimeFixture;

import org.hibernate.migration.testing.FixtureBundle;
import java.nio.file.Path;
import java.util.Map;

/// Generates every declared integration variant independently of JUnit selection or order.
/// @author Steve Ebersole
public final class ConvertedFixtureGenerator {
    private ConvertedFixtureGenerator() {}
    public static void main(String[] args) throws Exception {
        var environments = ApiValidation.environments();
        var context = environments.primary();
        var entries = FixtureBundle.catalog(context.migration());
        FixtureBundle.publish(Path.of(System.getProperty("transformedFixtures")), context.migration(), context.target(), entries, environments, entry -> {
            if (entry.scenario().startsWith("xml-")) return XmlRuntimeFixtures.convert(entry);
            org.openrewrite.Recipe recipe;
            Map<String, String> sources;
            if (entry.fixture().equals("runtime")) {
                recipe = ApiValidation.composite(entry.recipe());
                sources = Map.of("fixture/Migrated.java", RuntimeFixture.source());
            }
            else if (entry.fixture().startsWith("temporal")) {
                var target = MigrateTemporalAnnotation.TimestampTarget.valueOf(entry.fixture().substring("temporal".length()).toUpperCase(java.util.Locale.ROOT));
                recipe = new MigrateTemporalAnnotation(target, true, "+02:00", "Europe/Paris",
                        MigrateTemporalAnnotation.LocalTimezoneSource.ZONE_ID);
                if (!entry.recipe().equals(recipe.getName())) throw new IllegalArgumentException("Wrong temporal recipe");
                sources = Map.of("fixture/TemporalEntity.java", TemporalRuntimeFixture.source());
            }
            else throw new IllegalArgumentException("Unknown fixture " + entry.fixture());
            var result = ApiValidation.run(recipe, sources, environments.context(entry.variant()));
            if (!result.skipped().isEmpty()) throw new IllegalStateException("Unexpected skips for " + entry.id());
            return result.files();
        });
    }
}
