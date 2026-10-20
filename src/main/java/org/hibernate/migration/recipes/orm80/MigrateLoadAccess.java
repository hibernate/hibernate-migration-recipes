package org.hibernate.migration.recipes.orm80;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.openrewrite.Recipe;

/// Migrates supported fluent loading chains across the five Session loader factories.
///
/// @author Steve Ebersole
public class MigrateLoadAccess extends Recipe {
    @Override
    public @NonNull String getDisplayName() { return "Migrate fluent Session loading APIs"; }
    @Override
    public @NonNull String getDescription() {
        return "Migrates supported completed loader chains to find operations, preserving legacy defaults and reporting unsupported usages.";
    }
    @Override
    public @NonNull List<Recipe> getRecipeList() {
        return List.of(new MigrateById(), new MigrateByMultipleIds(), new MigrateByNaturalId(),
                new MigrateBySimpleNaturalId(), new MigrateByMultipleNaturalId());
    }
}
