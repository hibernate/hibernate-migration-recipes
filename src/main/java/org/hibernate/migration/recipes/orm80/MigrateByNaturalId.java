package org.hibernate.migration.recipes.orm80;

/// Migrates supported completed Session.byNaturalId loading chains.
///
/// @author Steve Ebersole
public class MigrateByNaturalId extends LoadAccessRecipe {
    public MigrateByNaturalId() {
        super("byNaturalId", "NaturalIdLoadAccess", false, true);
    }
}
