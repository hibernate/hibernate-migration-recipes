package org.hibernate.migration.recipes.orm80;

/// Migrates supported completed Session.byMultipleIds loading chains.
///
/// @author Steve Ebersole
public class MigrateByMultipleIds extends LoadAccessRecipe {
    public MigrateByMultipleIds() {
        super("byMultipleIds", "MultiIdentifierLoadAccess", true, false);
    }
}
