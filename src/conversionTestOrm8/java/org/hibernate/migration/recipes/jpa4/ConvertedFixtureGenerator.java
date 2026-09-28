/* SPDX-License-Identifier: Apache-2.0 */
package org.hibernate.migration.recipes.jpa4;

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
            if (!entry.fixture().equals("runtime")) throw new IllegalArgumentException("Unknown fixture " + entry.fixture());
            var result = ApiValidation.run(ApiValidation.composite(entry.recipe()), Map.of("fixture/Migrated.java", RuntimeFixture.source()), environments.context(entry.variant()));
            if (!result.skipped().isEmpty()) throw new IllegalStateException("Unexpected skips for " + entry.id());
            return result.files();
        });
    }
}
