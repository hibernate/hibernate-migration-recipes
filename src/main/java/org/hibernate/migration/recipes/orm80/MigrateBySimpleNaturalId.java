package org.hibernate.migration.recipes.orm80;

/// Migrates supported completed Session.bySimpleNaturalId loading chains.
///
/// @author Steve Ebersole
public class MigrateBySimpleNaturalId extends LoadAccessRecipe {
    public MigrateBySimpleNaturalId() {
        super("bySimpleNaturalId", "SimpleNaturalIdLoadAccess", false, true);
    }
}
