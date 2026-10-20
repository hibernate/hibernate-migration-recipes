package org.hibernate.migration.recipes.orm80;

/// Migrates supported completed Session.byMultipleNaturalId loading chains.
///
/// @author Steve Ebersole
public class MigrateByMultipleNaturalId extends LoadAccessRecipe {
    public MigrateByMultipleNaturalId() {
        super("byMultipleNaturalId", "NaturalIdMultiLoadAccess", true, true);
    }
}
