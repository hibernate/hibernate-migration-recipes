/* SPDX-License-Identifier: Apache-2.0 */
package org.hibernate.migration.recipes.temporal;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.J;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies that completed decisions are independent of their construction collections.
/// @author Steve Ebersole
class TemporalConversionPlanUnitTest {
    @Test void constructionCollectionsCannotChangeCompletedPlan() {
        J.CompilationUnit source = (J.CompilationUnit) JavaParser.fromJavaVersion().build()
                .parse("@Deprecated class Example {}").findFirst().orElseThrow();
        J.Annotation annotation = source.getClasses().getFirst().getLeadingAnnotations().getFirst();
        var property = new TemporalConversionPlan.Property(annotation, "java.util.Date", "java.time.Instant", "TIMESTAMP", null);
        var annotations = new HashMap<UUID, TemporalConversionPlan.Property>();
        annotations.put(annotation.getId(), property);
        UUID expression = UUID.randomUUID();
        var actions = new HashMap<UUID, TemporalConversionPlan.ValueAction>();
        actions.put(expression, TemporalConversionPlan.ValueAction.passThrough());
        var plan = new TemporalConversionPlan(annotations, Map.of(), Map.of(), Map.of(), actions);
        annotations.clear();
        actions.clear();
        assertSame(property, plan.annotation(annotation.getId()));
        assertEquals(TemporalConversionPlan.ValueKind.PASS_THROUGH, plan.value(expression).kind());
    }
}
