package org.hibernate.migration.recipes.temporal;

import org.hibernate.migration.recipes.support.SourcePositions;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.hibernate.migration.recipes.table.SkippedMigrations;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.Validated;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/// Replaces `@Temporal` / `TemporalType` usages, which were deprecated in Jakarta Persistence 3.2
/// in favor of Java Time.
///
/// The migration has 2 parts - types and values.
///
/// ## Migrating Type
///
/// Type migration is straight-forward:
///
/// * `@Temporal(DATE)` is always migrated to [java.time.LocalDate]
/// * `@Temporal(TIME)` is always migrated to [java.time.LocalTime]
/// * `@Temporal(TIMESTAMP)` is migrated to the type indicated by [#timestampTarget]
///
/// ## Migrating Value
///
/// Migrating specific value references is a bit more involved.
/// Simple migrations such as initializers, assignments, etc. are supported.
/// If a required value conversion is unsupported, the affected attribute migration remains unchanged and is reported in the OpenRewrite datatables.
///
/// This implementation coordinates scalar fields, direct getters and setters, initializers,
/// assignments, and supported callers across the supplied sources. Getter-based property mappings
/// and local aliases are supported. Complex accessors, escaping values, legacy temporal operations,
/// collection mappings, and additional mapping/code-generation annotations require review.
/// A blocker leaves the whole property and dependent property conversions unchanged.
/// Consumers outside the supplied sources and custom runtime Calendar timezone implementations
/// cannot be established by this analysis.
///
/// The recipe defines a number of options to control the value migration depending on the `TemporalType` and source type.
///
/// ### @Temporal(DATE)
///
/// The timezone used for value migration depends on whether the source is a `Calendar` or a `Date`.
/// For `Calendar`, if [#honorCalendarTimeZone] is enabled, the timezone associated with that `Calendar` is used.
/// Otherwise, the source is controlled by [#localTimezoneSource]; see [LocalTimezoneSource].
///
/// ### @Temporal(TIME)
///
/// The timezone used for the migration depends on whether the source is a `Calendar` or a `Date`.
/// For `Calendar`, if [#honorCalendarTimeZone] is enabled, the timezone associated with that `Calendar` is used.
/// Otherwise, the source is controlled by [#localTimezoneSource]; see [LocalTimezoneSource].
///
/// ### @Temporal(TIMESTAMP)
///
/// The migration for `TIMESTAMP` varies based on [#timestampTarget]...
///
/// #### TIMESTAMP -> INSTANT
///
/// Simple migration to [java.time.Instant]
///
/// #### TIMESTAMP -> OFFSET
///
/// If the source is a [java.util.Calendar] and [#honorCalendarTimeZone] is enabled, derive the offset from the
/// Calendar's timezone at the Calendar's instant, including any daylight-saving adjustment.
/// Otherwise, use [#offset].
///
/// #### TIMESTAMP -> ZONED
///
/// If the source is a [java.util.Calendar] and [#honorCalendarTimeZone] is enabled, the timezone associated  with that `Calendar` is used.
/// Otherwise, [#zoneId] is used.
///
/// #### TIMESTAMP -> LOCAL
///
/// If the source is a [java.util.Calendar] and [#honorCalendarTimeZone] is enabled, the timezone associated  with that `Calendar` is used.
/// Otherwise, [#localTimezoneSource] controls where we get timezone information.
///
/// ## Processing Flow
///
/// This [ScanningRecipe] separates analysis from editing so a caller may appear before
/// its entity or embeddable without affecting the conversion decision.
///
/// 1. **[Scan the original sources][#getScanner(Analysis)].** Collect every supplied compilation unit and record
///    original source locations for diagnostics. No source is changed during scanning.
/// 2. **[Plan properties and their uses][TemporalConversionPlanner#build(ExecutionContext)].** Before the first edit, identify temporal mappings,
///    backing fields, and direct accessors across all collected sources. Follow supported
///    local aliases and inspect callers using original attributed field and method identities.
///    Record where legacy values need conversion and which properties depend on one another.
/// 3. **[Resolve blockers][TemporalConversionPlanner#propagateRejections()].** Reject unsupported mappings or usages, then propagate rejection
///    through connected properties until no decisions change. This prevents a field, its
///    accessors, or dependent callers from being only partially migrated.
/// 4. **[Apply accepted plans][TemporalConversionVisitor#visitCompilationUnit(J.CompilationUnit, ExecutionContext)].** Update declarations, callers, and type attribution together.
///    Convert legacy values only at planned boundaries, preserving null and single evaluation;
///    transfers between compatible converted properties need no additional conversion.
///    Leave rejected properties unchanged and report them through [SkippedMigrations].
///
/// The recipe completes the plan once before delegating to [TemporalConversionVisitor].
/// The visitor reuses that immutable plan for subsequent files and makes no new decisions.
/// Original tree IDs and symbol identities connect planned edits to their targets even
/// after other declarations have changed. Analysis state belongs to one recipe execution;
/// consumers outside the supplied sources are not included in that plan.
///
/// @author Jennifer Joby
/// @author Steve Ebersole
public class MigrateTemporalAnnotation extends ScanningRecipe<MigrateTemporalAnnotation.Analysis> {

    /// Target for `@Temporal(TIMESTAMP)` migrations
    public enum TimestampTarget {
        /// Target [java.time.Instant]
        INSTANT,
        /// Target [java.time.LocalDateTime]
        LOCAL,
        /// Target [java.time.OffsetDateTime]
        OFFSET,
        /// Target [java.time.ZonedDateTime]
        ZONED
    }

    /// Source for timezone selection for [java.time.LocalDate], [java.time.LocalTime] and [java.time.LocalDateTime]
    /// migrations, unless the source is a [java.util.Calendar] and [#honorCalendarTimeZone] is enabled.
    public enum LocalTimezoneSource {
        /// Use [#offset]
        OFFSET,
        /// Use [#zoneId]
        ZONE_ID,
        /// Use [ZoneId#systemDefault()]
        SYSTEM
    }

    @Override
    public @NonNull String getDisplayName() {
        return "Replace @Temporal with java.time property types";
    }

    @Override
    public @NonNull String getDescription() {
        return "Migrates supported @Temporal Date and Calendar properties, accessors, and callers "
               + "to java.time types selected by the recipe options. Leaves properties with unsupported "
               + "dependent usages unchanged and reports them for review.";
    }

    @Option( displayName = "Target type for TemporalType#TIMESTAMP migrations",
            required = false,
            description = "Target Java type when migrating @Temporal(TIMESTAMP).  " +
                          "`INSTANT` (default) targets `java.time.Instant` and requires neither an `offset` nor a `zoneId`.  " +
                          "`LOCAL` targets `java.time.LocalDateTime`; see `localTimezoneSource`.  " +
                          "`OFFSET` targets `java.time.OffsetDateTime` and uses `offset`, unless `honorCalendarTimeZone` applies to the source value.  " +
                          "`ZONED` targets `java.time.ZonedDateTime` and uses `zoneId`, unless `honorCalendarTimeZone` applies to the source value.")
    public TimestampTarget timestampTarget = TimestampTarget.INSTANT;

    @Option( displayName = "Honor Calendar timezone",
            required = false,
            description = "When converting `Calendar` values to `LocalDate`, `LocalTime`, `LocalDateTime`, `OffsetDateTime`, or `ZonedDateTime`, use the Calendar's timezone instead of the configured timezone source, offset, or zone.  " +
                          "For `OffsetDateTime`, derive the offset at the Calendar's instant, including any daylight-saving adjustment.  " +
                          "For `ZonedDateTime`, retain the Calendar's timezone as a `ZoneId` where representable. " +
                          "When false, use the configuration applicable to the target type. " +
                          "Has no effect on `Date` source values or conversion to `Instant`.  " +
                          "Defaults to true.  " +
                          "Visible custom timezone construction or mutation leaves affected Calendar value migrations unchanged and is reported. " +
                          "Timezone implementations supplied by external code require manual review."
    )
    @Nullable
    Boolean honorCalendarTimeZone;

    @Option( displayName = "UTC offset",
            required = false,
            description = "Fixed UTC offset used when `timestampTarget` is `OFFSET`, or when `localTimezoneSource` is "
                          + "`OFFSET` for conversion to `LocalDate`, `LocalTime`, or `LocalDateTime`. For example, `+02:00` or `Z`. " +
                          "Must be accepted by `java.time.ZoneOffset.of()`. " +
                          "A fixed offset does not apply daylight-saving rules. " +
                          "When `honorCalendarTimeZone` is `true` and the source value is a `Calendar`, the Calendar's timezone takes precedence. " +
                          "Required only when a value conversion selects this setting.  " +
                          "If required and absent, the affected attribute migration remains unchanged and is reported.  " +
                          "Does not configure Hibernate's JDBC timezone or temporal storage.",
            example = "+02:00"
    )
    @Nullable
    String offset;

    @Option( displayName = "Zone ID",
            required = false,
            description = "Timezone ID used when `timestampTarget` is `ZONED`, or when `localTimezoneSource` is `ZONE_ID` for conversion to `LocalDate`, `LocalTime`, or `LocalDateTime`.  " +
                          "Must be accepted by java.time.ZoneId.of().  " +
                          "The zone's rules determine the offset at the source instant.  " +
                          "When `honorCalendarTimeZone` is `true` and the source value is a `Calendar`, the Calendar's timezone takes precedence.  " +
                          "Required only when a value conversion selects this setting.  " +
                          "If required and absent, the affected attribute migration remains unchanged and is reported.  " +
                          "Does not configure Hibernate's JDBC timezone or temporal storage.",
            example = "America/New_York"
    )
    @Nullable
    String zoneId;

    @Option( displayName = "Timezone source for local zone-based value migration",
            required = false,
            description = "Timezone source used when converting values to `LocalDate`, `LocalTime`, or `LocalDateTime`.  " +
                          "`OFFSET` uses the configured `offset`; " +
                          "`ZONE_ID` uses the configured `zoneId`; " +
                          "`SYSTEM` uses `ZoneId.systemDefault()` at application runtime. " +
                          "When `honorCalendarTimeZone` is `true`, the source Calendar's timezone takes precedence. " +
                          "Required only when a value conversion selects this setting.  " +
                          "If required and absent, the affected attribute migration remains unchanged and is reported.  " +
                          "Does not configure Hibernate's JDBC timezone or temporal storage." )
    @Nullable
    LocalTimezoneSource localTimezoneSource;

    private final transient SkippedMigrations skipped = new SkippedMigrations(this);

    public MigrateTemporalAnnotation() {}

    @JsonCreator
    public MigrateTemporalAnnotation(
            @JsonProperty("timestampTarget") @Nullable TimestampTarget timestampTarget,
            @JsonProperty("honorCalendarTimeZone") @Nullable Boolean honorCalendarTimeZone,
            @JsonProperty("offset") @Nullable String offset,
            @JsonProperty("zoneId") @Nullable String zoneId,
            @JsonProperty("localTimezoneSource") @Nullable LocalTimezoneSource localTimezoneSource) {
        this.timestampTarget = timestampTarget == null ? TimestampTarget.INSTANT : timestampTarget;
        this.honorCalendarTimeZone = honorCalendarTimeZone;
        this.offset = offset;
        this.zoneId = zoneId;
        this.localTimezoneSource = localTimezoneSource;
    }

    @Override
    public Validated<Object> validate() {
        Validated<Object> result = super.validate();
        try {
            if (offset != null) java.time.ZoneOffset.of(offset);
        }
        catch (java.time.DateTimeException ex) {
            result = result.and(Validated.invalid("offset", offset, "Must be a valid UTC offset."));
        }
        try {
            if (zoneId != null) ZoneId.of(zoneId);
        }
        catch (java.time.DateTimeException ex) {
            result = result.and(Validated.invalid("zoneId", zoneId, "Must be a valid ZoneId."));
        }
        return result;
    }

    /// Scanned inputs and the immutable result for one execution, never shared between runs.
    public static final class Analysis {
        private final List<J.CompilationUnit> inputs = new ArrayList<>();
        private final Map<UUID, SourcePositions> positions = new HashMap<>();
        private final TemporalValueConversion.Policy policy;
        private final boolean valid;
        private TemporalConversionPlan plan;

        private Analysis(TemporalValueConversion.Policy policy, boolean valid) {
            this.policy = policy;
            this.valid = valid;
        }
    }

    @Override
    public Analysis getInitialValue(ExecutionContext ctx) {
        return new Analysis(new TemporalValueConversion.Policy(timestampTarget, honorCalendarTimeZone,
                offset, zoneId, localTimezoneSource), validate().isValid());
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Analysis analysis) {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                analysis.inputs.add(cu);
                analysis.positions.put(cu.getId(), SourcePositions.capture(cu));
                return cu;
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Analysis analysis) {
        return new JavaIsoVisitor<ExecutionContext>() {
            private TemporalConversionVisitor delegate;

            @Override public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                if (!analysis.valid) return cu;
                if (analysis.plan == null) {
                    analysis.plan = new TemporalConversionPlanner(analysis.inputs, analysis.positions, analysis.policy).build(ctx);
                }
                if (delegate == null) delegate = new TemporalConversionVisitor(MigrateTemporalAnnotation.this, skipped, analysis.plan);
                return (J.CompilationUnit) delegate.visit(cu, ctx, getCursor().getParent());
            }
        };
    }
}
