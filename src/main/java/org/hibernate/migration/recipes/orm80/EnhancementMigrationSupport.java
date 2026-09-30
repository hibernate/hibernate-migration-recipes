package org.hibernate.migration.recipes.orm80;

import org.hibernate.migration.recipes.table.SkippedMigrations;
import org.jspecify.annotations.NonNull;
import org.openrewrite.*;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.marker.Marker;
import org.openrewrite.marker.SearchResult;
import org.openrewrite.xml.XmlIsoVisitor;
import org.openrewrite.xml.tree.Xml;

import java.util.*;
import java.util.function.UnaryOperator;

/// Original-source diagnostics and option names shared by enhancement migrations.
///
/// @author Steve Ebersole
final class EnhancementMigrationSupport {
    static final String OLD = "enableExtendedEnhancement";
    static final String NEW = "enableClientEnhancement";
    static final String IDENTITY = "ENHANCEMENT_IDENTITY_UNRESOLVED";
    static final String AMBIGUOUS = "ENHANCEMENT_CONFIGURATION_AMBIGUOUS";
    static final String INHERITANCE = "ENHANCEMENT_INHERITANCE_UNRESOLVED";
    static final String SYNTAX = "ENHANCEMENT_SYNTAX_UNSUPPORTED";

    private EnhancementMigrationSupport() {}

    /// Records candidate offsets through the source's own printer; temporary markers
    /// belong only to this snapshot and never reach migration output.
    static Map<UUID, Integer> offsets(SourceFile source, Set<UUID> candidates) {
        SourceFile marked;
        if (source instanceof Xml.Document) {
            marked = (SourceFile) new XmlIsoVisitor<Integer>() {
                @Override public Xml preVisit(@NonNull Xml tree, @NonNull Integer p) {
                    return candidates.contains(tree.getId())
                            ? tree.withMarkers(tree.getMarkers().add(new SearchResult(tree.getId(), null))) : tree;
                }
            }.visit(source, 0);
        }
        else {
            marked = (SourceFile) new JavaIsoVisitor<Integer>() {
                @Override public J preVisit(@NonNull J tree, @NonNull Integer p) {
                    return candidates.contains(tree.getId())
                            ? tree.withMarkers(tree.getMarkers().add(new SearchResult(tree.getId(), null))) : tree;
                }
            }.visit(source, 0);
        }
        Map<UUID, Integer> offsets = new HashMap<>();
        var out = getOut( candidates, offsets );
        Objects.requireNonNull(marked).printAll(out);
        return offsets;
    }

    private static @NonNull PrintOutputCapture<Integer> getOut(Set<UUID> candidates, Map<UUID, Integer> offsets) {
        List<PrintOutputCapture<Integer>> holder = new ArrayList<>();
        var out = new PrintOutputCapture<>(0, new PrintOutputCapture.MarkerPrinter() {
            @Override public @NonNull String beforeSyntax(
					@NonNull Marker marker,
					@NonNull Cursor cursor,
					@NonNull UnaryOperator<String> wrapper) {
                if ( candidates.contains(marker.getId())) {
                    int offset = holder.get(0).out.length();
                    Object tree = cursor.getParentOrThrow().getValue();
                    if (tree instanceof Xml.Tag) offset++;
                    if (tree instanceof Xml.Attribute a) offset += a.getKey().getPrefix().length();
                    offsets.put(marker.getId(), offset);
                }
                return "";
            }
        });
        holder.add(out);
        return out;
    }

    /// Reports each candidate once per execution, retaining original input coordinates.
    static void report(Recipe recipe, SkippedMigrations table, SourceFile source,
            Map<UUID, Integer> offsets, UUID candidate, String reason, String message, ExecutionContext ctx) {
        String key = recipe.getName() + ":" + source.getSourcePath() + ":" + candidate;
        Set<String> reported = ctx.computeMessageIfAbsent(EnhancementMigrationSupport.class.getName(),
                k -> java.util.concurrent.ConcurrentHashMap.newKeySet());
        if (!reported.add(key)) return;
        Integer offset = offsets.get(candidate);
        if (offset == null) throw new IllegalStateException("Missing enhancement candidate position: " + candidate);
        String text = source.printAll();
        int line = 1, column = 1;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') { line++; column = 1; }
            else column++;
        }
        table.insertRow(ctx, new SkippedMigrations.Row(recipe.getName(), source.getSourcePath().toString(),
                line, column, OLD, reason, message));
    }
}
