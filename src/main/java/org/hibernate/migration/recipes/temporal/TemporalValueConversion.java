package org.hibernate.migration.recipes.temporal;

import java.util.Objects;
import org.openrewrite.Cursor;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.tree.Expression;
import org.hibernate.migration.recipes.temporal.MigrateTemporalAnnotation.TimestampTarget;
import org.hibernate.migration.recipes.temporal.MigrateTemporalAnnotation.LocalTimezoneSource;

/// A selected legacy-value conversion, independent of mutable recipe options.
/// Planning selects the timezone expression; editing only supplies the original expression,
/// its cursor, and a collision-free temporary parameter name.
/// @author Steve Ebersole
record TemporalValueConversion(String source, String target, ZoneSource zoneSource, String zone) {
    enum ZoneSource { NONE, CALENDAR, CONFIGURED }

    TemporalValueConversion {
        Objects.requireNonNull(source);
        Objects.requireNonNull(target);
        Objects.requireNonNull(zoneSource);
        if (zoneSource == ZoneSource.CONFIGURED) Objects.requireNonNull(zone);
    }

    /// Immutable configuration snapshot for one recipe execution.
    record Policy(TimestampTarget timestampTarget, Boolean honorCalendarTimeZone,
            String offset, String zoneId, LocalTimezoneSource localTimezoneSource) {
        String target(String precision) {
            if ("DATE".equals(precision)) return "java.time.LocalDate";
            if ("TIME".equals(precision)) return "java.time.LocalTime";
            if (!"TIMESTAMP".equals(precision)) return null;
            return switch ( timestampTarget == null ? TimestampTarget.INSTANT : timestampTarget ) {
                case LOCAL -> "java.time.LocalDateTime";
                case OFFSET -> "java.time.OffsetDateTime";
                case ZONED -> "java.time.ZonedDateTime";
                default -> "java.time.Instant";
            };
        }

        String configuredZone(String target) {
            if ("java.time.OffsetDateTime".equals(target)) return offset == null ? null : "java.time.ZoneOffset.of(" + quoted(offset) + ")";
            if ("java.time.ZonedDateTime".equals(target)) return zoneId == null ? null : "java.time.ZoneId.of(" + quoted(zoneId) + ")";
            if (localTimezoneSource == null) return null;
            return switch ( localTimezoneSource ) {
                case OFFSET -> offset == null ? null : "java.time.ZoneOffset.of(" + quoted( offset ) + ")";
                case ZONE_ID -> zoneId == null ? null : "java.time.ZoneId.of(" + quoted( zoneId ) + ")";
                default -> "java.time.ZoneId.systemDefault()";
            };
        }

        private static String quoted(String value) {
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }

        TemporalValueConversion conversion(String source, String target) {
            if ("java.time.Instant".equals(target)) return new TemporalValueConversion(source, target, ZoneSource.NONE, null);
            if ("java.util.Calendar".equals(source) && !Boolean.FALSE.equals(honorCalendarTimeZone))
                return new TemporalValueConversion(source, target, ZoneSource.CALENDAR, null);
            return new TemporalValueConversion(source, target, ZoneSource.CONFIGURED, configuredZone(target));
        }
    }

    Expression apply(Expression value, Cursor cursor, String parameter) {
        // Timestamp may arrive through a Date reference; preserve its nanoseconds. Other
        // Date values use epoch millis because java.sql.Date.toInstant() can throw.
        String instant = "java.util.Calendar".equals(source) ? parameter + ".toInstant()"
                : "(" + parameter + " instanceof java.sql.Timestamp ? ((java.sql.Timestamp) " + parameter
                + ").toInstant() : java.time.Instant.ofEpochMilli(" + parameter + ".getTime()))";
        String converted = instant;
        if (!"java.time.Instant".equals(target)) {
            String selectedZone = zoneSource == ZoneSource.CALENDAR ? parameter + ".getTimeZone().toZoneId()" : zone;
            converted = instant + ".atZone(" + selectedZone + ")";
            if (!"java.time.ZonedDateTime".equals(target)) converted += ".to" + target.substring(target.lastIndexOf('.') + 1) + "()";
        }
        // The original value is evaluated once, after the receiver when used in a setter call.
        String code = "((java.util.function.Function<" + source + ", " + target + ">) "
                + parameter + " -> " + parameter + " == null ? null : " + converted + ").apply(#{any(" + source + ")})";
        return JavaTemplate.builder(code).contextSensitive().build().apply(cursor, value.getCoordinates().replace(), value);
    }
}
