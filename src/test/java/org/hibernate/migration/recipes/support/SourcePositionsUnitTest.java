/* SPDX-License-Identifier: Apache-2.0 */
package org.hibernate.migration.recipes.support;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Space;

import static org.junit.jupiter.api.Assertions.*;

/// Checks diagnostic coordinate conventions and preservation of original tree locations.
/// @author Steve Ebersole
class SourcePositionsUnitTest {
    @Test void preservesExistingCoordinatesAtEveryOffset() {
        for (String source : new String[] {"", "abc", "\n", "a\r\n\tb\n\n", "\t😀x\ry\nlast"}) {
            SourcePositions positions = new SourcePositions(source);
            for (int offset = 0; offset <= source.length(); offset++) {
                assertArrayEquals(MigrationSupport.position(source, offset), positions.position(offset),
                        "Offset " + offset + " in " + source);
            }
        }
        assertArrayEquals(new int[]{1, 4}, new SourcePositions("\t😀x").position(3));
        assertArrayEquals(new int[]{2, 1}, new SourcePositions("a\r\n").position(3));
    }

    @Test void handlesLargeLineIndexAndBounds() {
        SourcePositions positions = new SourcePositions("x\n".repeat(10_000));
        assertArrayEquals(new int[]{10_000, 2}, positions.position(19_999));
        assertArrayEquals(new int[]{10_001, 1}, positions.position(20_000));
        assertThrows(IndexOutOfBoundsException.class, () -> positions.position(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> positions.position(20_001));
        assertThrows(IllegalStateException.class, () -> positions.position(UUID.randomUUID()));
    }

    @Test void capturesOriginalTreePositionsBeforeEdits() {
        J.CompilationUnit source = (J.CompilationUnit) JavaParser.fromJavaVersion().build()
                .parse("// header\r\nclass Example {}\r\n").findFirst().orElseThrow();
        SourcePositions positions = SourcePositions.capture(source);
        J.ClassDeclaration changed = source.getClasses().getFirst().withPrefix(Space.format("\n\n\n"));
        assertArrayEquals(new int[]{2, 1}, positions.position(changed.getId()));
        assertArrayEquals(new int[]{2, 7}, positions.position(changed.getName().getId()));
        assertEquals("2:7", positions.location(changed.getName().getId()));
    }
}
