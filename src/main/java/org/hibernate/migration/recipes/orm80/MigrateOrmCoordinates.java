package org.hibernate.migration.recipes.orm80;

import org.jspecify.annotations.NonNull;
import org.openrewrite.*;
import java.util.List;

/// Relocates ORM coordinates and aligns build declarations to a selected ORM 8 release.
///
/// @author Steve Ebersole
public class MigrateOrmCoordinates extends Recipe {
    @Option(displayName = "Target ORM version", description = "An exact published ORM 8.0 release.", example = "8.0.0.Beta3")
    private final String targetVersion;

    public MigrateOrmCoordinates(String targetVersion) { this.targetVersion = targetVersion; }
    public String getTargetVersion() { return targetVersion; }
    @Override public @NonNull String getDisplayName() { return "Migrate Hibernate ORM coordinates"; }
    @Override public @NonNull String getDescription() { return "Relocates supported ORM dependencies, aligns tooling, and adopts the Hibernate platform in supported builds."; }
    @Override public @NonNull Validated<Object> validate() { return super.validate().and(OrmCoordinateSupport.validate(targetVersion)); }
    @Override public @NonNull List<Recipe> getRecipeList() {
        return List.of(new MigrateGradleOrmCoordinates(targetVersion), new MigrateMavenOrmCoordinates(targetVersion), new MigrateAntOrmCoordinates(targetVersion));
    }
}
