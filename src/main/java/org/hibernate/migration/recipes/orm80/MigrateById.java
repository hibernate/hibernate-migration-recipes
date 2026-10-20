package org.hibernate.migration.recipes.orm80;

/// Migrates supported completed Session.byId loading chains.
///
/// @author Steve Ebersole
public class MigrateById extends LoadAccessRecipe {
    public MigrateById() {
        super("byId", "IdentifierLoadAccess", false, false);
    }
}
