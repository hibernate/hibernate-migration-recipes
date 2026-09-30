package org.hibernate.migration.recipes.jpa4;

import org.hibernate.migration.recipes.table.SkippedMigrations;
import org.hibernate.migration.recipes.xml.Descriptor;
import org.hibernate.migration.recipes.xml.DescriptorVisitor;
import org.jspecify.annotations.NonNull;
import org.openrewrite.*;

/// Updates Jakarta entity-mapping versions and canonical schema references from 3.x to 4.0.
///
/// @author Steve Ebersole
public class MigrateOrmXml extends Recipe {
    private final transient SkippedMigrations skipped = new SkippedMigrations(this);

    @Override public @NonNull String getDisplayName() { return "Migrate orm.xml to JPA 4.0"; }
    @Override public @NonNull String getDescription() { return "Updates Jakarta entity-mapping versions and canonical schema references from 3.x to 4.0."; }
    @Override public @NonNull TreeVisitor<?, ExecutionContext> getVisitor() {
        return new DescriptorVisitor(this, skipped, Descriptor.ORM);
    }
}
