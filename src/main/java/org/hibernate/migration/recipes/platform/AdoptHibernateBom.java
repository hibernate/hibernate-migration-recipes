package org.hibernate.migration.recipes.platform;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.openrewrite.Option;

import static org.hibernate.migration.recipes.support.OrmCoordinateSupport.BOM;

/// Imports the ORM-only Hibernate BOM while preserving application dependency management.
///
/// @author Steve Ebersole
public class AdoptHibernateBom extends HibernateBomAdoption {
    @Option(displayName = "Target ORM version", required = false,
            description = "An exact published ORM 8.0 release. Defaults to the version this recipe JAR was built against.",
            example = "8.0.0.CR1")
    private final String targetVersion;

    public AdoptHibernateBom() { this(null); }

    @JsonCreator
    public AdoptHibernateBom(@JsonProperty("targetVersion") @Nullable String targetVersion) {
        this.targetVersion = targetVersion != null ? targetVersion : defaultTargetVersion();
    }
    @Override public String getTargetVersion() { return targetVersion; }
    @Override protected String bomArtifact() { return BOM; }
    @Override public @NonNull String getDisplayName() { return "Adopt Hibernate ORM BOM"; }
    @Override public @NonNull String getDescription() { return "Imports the ORM-only Hibernate BOM while preserving application dependency management."; }
}
