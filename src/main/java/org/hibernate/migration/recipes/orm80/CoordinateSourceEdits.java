package org.hibernate.migration.recipes.orm80;

import org.openrewrite.*;
import org.openrewrite.groovy.GroovyParser;
import org.openrewrite.kotlin.KotlinParser;
import org.openrewrite.toml.TomlParser;
import org.openrewrite.toml.TomlIsoVisitor;
import org.openrewrite.toml.tree.Toml;
import org.openrewrite.marker.Marker;
import org.openrewrite.marker.SearchResult;
import java.util.*;
import java.util.function.UnaryOperator;

/// Applies nonoverlapping edits to structurally identified script/catalog nodes and reparses the result.
///
/// @author Steve Ebersole
final class CoordinateSourceEdits {
    record Edit(int start, int end, String replacement) {}
    private CoordinateSourceEdits() {}
    static Map<UUID, Integer> offsets(SourceFile source, Set<UUID> ids) {
        if (!(source instanceof Toml.Document)) return EnhancementMigrationSupport.offsets(source, ids);
        SourceFile marked = (SourceFile) new TomlIsoVisitor<Integer>() {
            @Override public Toml preVisit(Toml tree, Integer p) {
                return ids.contains(tree.getId()) ? tree.withMarkers(tree.getMarkers().add(new SearchResult(tree.getId(), null))) : tree;
            }
        }.visit(source, 0);
        Map<UUID, Integer> positions = new HashMap<>();
        List<PrintOutputCapture<Integer>> holder = new ArrayList<>();
        var out = new PrintOutputCapture<>(0, new PrintOutputCapture.MarkerPrinter() {
            @Override public String beforeSyntax(Marker marker, Cursor cursor, UnaryOperator<String> wrapper) {
                if (ids.contains(marker.getId())) positions.put(marker.getId(), holder.get(0).out.length());
                return "";
            }
        });
        holder.add(out); Objects.requireNonNull(marked).printAll(out); return positions;
    }
    static String printed(SourceFile source, Tree tree) {
        if (tree instanceof org.openrewrite.java.tree.J j) tree = j.withPrefix(org.openrewrite.java.tree.Space.EMPTY);
        if (tree instanceof Toml t) tree = t.withPrefix(org.openrewrite.toml.tree.Space.EMPTY);
        PrintOutputCapture<Integer> out = new PrintOutputCapture<>(0);
        source.<Integer>printer(new Cursor(null, source)).visit(tree, out);
        return out.getOut();
    }
    static SourceFile apply(SourceFile source, List<Edit> edits, ExecutionContext ctx) {
        if (edits.isEmpty()) return source;
        String original = source.printAll();
        StringBuilder text = new StringBuilder(original);
        List<Edit> sorted = edits.stream().distinct().sorted(Comparator.comparingInt(Edit::start).reversed()
                .thenComparing(Comparator.comparingInt(Edit::end).reversed())).toList();
        int previous = original.length() + 1;
        for (Edit edit : sorted) {
            if (edit.end > previous) throw new IllegalStateException("Overlapping coordinate edits in " + source.getSourcePath());
            text.replace(edit.start, edit.end, edit.replacement); previous = edit.start;
        }
        if (original.contentEquals(text)) return source;
        Parser parser = source instanceof Toml.Document ? TomlParser.builder().build()
                : source.getSourcePath().toString().endsWith(".kts") ? KotlinParser.builder().isKotlinScript(true).build()
                : GroovyParser.builder().build();
        SourceFile parsed = parser.parse(ctx, text.toString()).findFirst().orElseThrow();
        if (!source.getClass().equals(parsed.getClass()) || !parsed.printAll().equals(text.toString()))
            throw new IllegalStateException("Coordinate edit did not round-trip: " + source.getSourcePath());
        return parsed.<SourceFile>withId(source.getId()).withSourcePath(source.getSourcePath()).<SourceFile>withMarkers(source.getMarkers())
                .withCharset(source.getCharset()).withFileAttributes(source.getFileAttributes());
    }
}
