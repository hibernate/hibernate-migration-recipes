package org.hibernate.migration.recipes.support;

import org.jspecify.annotations.NonNull;
import org.openrewrite.PrintOutputCapture;
import org.openrewrite.java.JavaPrinter;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Space;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/// Indexes positions in an original source snapshot without retaining its text.
///
/// Line starts are collected in linear time. Each requested position uses binary search;
/// coordinates are not computed or formatted for nodes that never need a diagnostic.
/// Lines and columns are one-based. Only LF advances the line; columns count UTF-16
/// code units, so tabs count as one and supplementary characters count as two.
///
/// @author Steve Ebersole
public final class SourcePositions {
    private final int sourceLength;
    private final int[] lineStarts;
    private final Map<UUID, Integer> offsets;

    SourcePositions(String source) {
        this(source, Map.of());
    }

    private SourcePositions(String source, Map<UUID, Integer> offsets) {
        this.sourceLength = source.length();
        this.offsets = Map.copyOf(offsets);
        int[] starts = new int[128];
        int count = 1;
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                if (count == starts.length) starts = Arrays.copyOf(starts, count * 2);
                starts[count++] = i + 1;
            }
        }
        this.lineStarts = Arrays.copyOf(starts, count);
    }

    /// Captures tree offsets before any edits, using the same printer positions as diagnostics.
    public static SourcePositions capture(J.CompilationUnit source) {
        Map<UUID, Integer> offsets = new HashMap<>();
        PrintOutputCapture<Integer> output = new PrintOutputCapture<>(0);
        new JavaPrinter<Integer>() {
            @Override
            protected void beforeSyntax(
                    @NonNull J tree,
                    Space.@NonNull Location location,
                    @NonNull PrintOutputCapture<Integer> out) {
                super.beforeSyntax(tree, location, out);
                // getOut() copies the accumulated text; read the buffer length directly.
                offsets.putIfAbsent(tree.getId(), out.out.length());
            }
        }.visit(source, output);
        return new SourcePositions(output.getOut(), offsets);
    }

    /// Resolves an original tree identity; rewritten trees may retain that same identity.
    int[] position(UUID treeId) {
        Integer offset = offsets.get(treeId);
        if (offset == null) throw new IllegalStateException("Missing original candidate position: " + treeId);
        return position(offset);
    }

    /// Resolves a UTF-16 offset, including end-of-file, to its one-based line and column.
    int[] position(int offset) {
        if (offset < 0 || offset > sourceLength) throw new IndexOutOfBoundsException(offset);
        int line = Arrays.binarySearch(lineStarts, offset);
        // For a nonmatching offset, the preceding line start owns the character.
        if (line < 0) line = -line - 2;
        return new int[] {line + 1, offset - lineStarts[line] + 1};
    }

    /// Formats coordinates only for a node whose location is being reported.
    public String location(UUID treeId) {
        int[] position = position(treeId);
        return position[0] + ":" + position[1];
    }
}
