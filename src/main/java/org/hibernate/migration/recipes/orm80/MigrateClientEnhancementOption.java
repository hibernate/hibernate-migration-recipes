package org.hibernate.migration.recipes.orm80;

import org.jspecify.annotations.NonNull;
import org.openrewrite.Recipe;
import java.util.List;

/// Renames the Hibernate enhancement option in supported Gradle, Maven, and Ant builds.
///
/// @author Steve Ebersole
public class MigrateClientEnhancementOption extends Recipe {
    @Override public @NonNull String getDisplayName() { return "Migrate Hibernate client enhancement option"; }
    @Override public @NonNull String getDescription() {
        return "Renames enableExtendedEnhancement to enableClientEnhancement, preserving the new option when both exist.";
    }
    @Override public @NonNull List<Recipe> getRecipeList() {
        return List.of(new MigrateGradleClientEnhancementOption(), new MigrateMavenClientEnhancementOption(),
                new MigrateAntClientEnhancementOption());
    }
}
