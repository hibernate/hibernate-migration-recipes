package org.hibernate.migration.recipes.platform;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.openrewrite.Option;

import static org.hibernate.migration.recipes.support.OrmCoordinateSupport.PLATFORM;

/// Imports the Hibernate platform BOM and omits explicit versions for managed library artifacts.
///
/// @author Steve Ebersole
public class AdoptHibernatePlatform extends HibernateBomAdoption {
    @Option(displayName = "Target ORM version", required = false,
            description = "An exact published ORM 8.0 release. Defaults to the version this recipe JAR was built against.",
            example = "8.0.0.CR1")
    private final String targetVersion;

    public AdoptHibernatePlatform() { this(null); }

    @JsonCreator
    public AdoptHibernatePlatform(@JsonProperty("targetVersion") @Nullable String targetVersion) {
        this.targetVersion = targetVersion != null ? targetVersion : defaultTargetVersion();
    }
    @Override public String getTargetVersion() { return targetVersion; }
    @Override protected String bomArtifact() { return PLATFORM; }
    @Override public @NonNull String getDisplayName() { return "Adopt Hibernate platform"; }
    @Override public @NonNull String getDescription() { return "Imports the Hibernate platform BOM and omits explicit versions for managed library artifacts."; }
}
