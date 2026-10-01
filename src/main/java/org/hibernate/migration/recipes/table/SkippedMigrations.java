package org.hibernate.migration.recipes.table;

import org.openrewrite.Column;
import org.openrewrite.DataTable;
import org.openrewrite.Recipe;

/// Reports migration candidates or portions of a migration that require manual review.
///
/// Leaf recipes insert rows identifying the source location and the reason manual
/// review is needed. This table records those decisions; it does not modify source
/// code or decide which candidates are supported. Query text is not a report column.
///
/// @author Steve Ebersole
public class SkippedMigrations extends DataTable<SkippedMigrations.Row> {
    /// Creates the report table owned by a migration recipe.
    ///
    /// @param recipe the recipe contributing skipped-candidate rows
    public SkippedMigrations(Recipe recipe) {
        super(recipe, "Skipped Hibernate migrations", "Source locations and reasons for migrations requiring manual review.");
    }

    /// A migration candidate and the explanation for skipping all or part of its conversion.
    ///
    /// Coordinates are one-based and refer to the leaf recipe's input, which may
    /// already contain changes made by earlier recipes in a composite migration.
    /// The reason code provides a stable identifier; the message explains the case
    /// for a person reviewing the report.
    public static class Row {
        @Column(displayName = "Recipe", description = "The leaf recipe identifier.")
        private final String recipe;
        @Column(displayName = "Source path", description = "Source-relative path.")
        private final String sourcePath;
        @Column(displayName = "Line", description = "One-based line in the leaf recipe input.")
        private final int line;
        @Column(displayName = "Column", description = "One-based column in the leaf recipe input.")
        private final int column;
        @Column(displayName = "Subject", description = "Annotation, method, or XML root.")
        private final String subject;
        @Column(displayName = "Reason code", description = "Stable reason for leaving all or part of the candidate unchanged.")
        private final String reasonCode;
        @Column(displayName = "Message", description = "Explanation of the unsupported input.")
        private final String message;

        /// Creates a report entry for a candidate left wholly or partially unchanged.
        ///
        /// @param recipe the leaf recipe identifier
        /// @param sourcePath the source-relative file path
        /// @param line the one-based line in the leaf recipe's input
        /// @param column the one-based column in the leaf recipe's input
        /// @param subject the annotation, method, or XML root requiring review
        /// @param reasonCode the stable identifier for the reason conversion was skipped
        /// @param message the explanation of the unsupported input, without query text
        public Row(String recipe, String sourcePath, int line, int column, String subject, String reasonCode, String message) {
            this.recipe = recipe; this.sourcePath = sourcePath; this.line = line; this.column = column;
            this.subject = subject; this.reasonCode = reasonCode; this.message = message;
        }

        /// @return the identifier of the leaf recipe that skipped the candidate
        public String getRecipe() { return recipe; }
        /// @return the source-relative path containing the candidate
        public String getSourcePath() { return sourcePath; }
        /// @return the one-based line in the leaf recipe's input
        public int getLine() { return line; }
        /// @return the one-based column in the leaf recipe's input
        public int getColumn() { return column; }
        /// @return the annotation, method, or XML root requiring review
        public String getSubject() { return subject; }
        /// @return the stable identifier for the reason conversion was skipped
        public String getReasonCode() { return reasonCode; }
        /// @return the explanation of the unsupported input
        public String getMessage() { return message; }
    }
}
