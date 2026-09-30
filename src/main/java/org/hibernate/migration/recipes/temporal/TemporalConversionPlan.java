package org.hibernate.migration.recipes.temporal;

import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/// Immutable decisions indexed by original tree and symbol identities.
/// Rejected properties have diagnostics but contribute no executable edits. Collection copies
/// contain independent records, never the planner's mutable candidates or dependency graph.
/// @author Steve Ebersole
final class TemporalConversionPlan {
    record Diagnostic(String subject, String reason, String message) {}
    record Property(J.Annotation annotation, String source, String target, String precision, Diagnostic diagnostic) {
        Property {
            Objects.requireNonNull(annotation);
            if (diagnostic == null) {
                Objects.requireNonNull(source);
                Objects.requireNonNull(target);
                Objects.requireNonNull(precision);
            }
        }
        boolean accepted() { return diagnostic == null; }
    }
    record Accessor(Property property, boolean getter, JavaType.Method originalMethod) {}
    enum ValueKind { CONVERT, PASS_THROUGH }
    record ValueAction(ValueKind kind, TemporalValueConversion conversion) {
        ValueAction {
            Objects.requireNonNull(kind);
            if ((kind == ValueKind.CONVERT) != (conversion != null)) throw new IllegalArgumentException("Invalid value action");
        }
        static ValueAction convert(TemporalValueConversion conversion) { return new ValueAction(ValueKind.CONVERT, conversion); }
        static ValueAction passThrough() { return new ValueAction(ValueKind.PASS_THROUGH, null); }
    }

    private final Map<UUID, Property> annotations;
    private final Map<String, Property> fields;
    private final Map<JavaType.Variable, Property> locals;
    private final Map<String, Accessor> accessors;
    private final Map<UUID, ValueAction> values;

    TemporalConversionPlan(Map<UUID, Property> annotations, Map<String, Property> fields,
            Map<JavaType.Variable, Property> locals, Map<String, Accessor> accessors, Map<UUID, ValueAction> values) {
        this.annotations = Map.copyOf(annotations);
        this.fields = Map.copyOf(fields);
        this.locals = Map.copyOf(locals);
        this.accessors = Map.copyOf(accessors);
        this.values = Map.copyOf(values);
    }

    Property annotation(UUID id) { return annotations.get(id); }
    Property variable(JavaType.Variable variable) {
        if (variable == null) return null;
        String key = fieldKey(variable);
        return key == null ? locals.get(variable) : fields.get(key);
    }
    Accessor accessor(JavaType.Method method) { return method == null ? null : accessors.get(methodKey(method)); }
    ValueAction value(UUID id) { return values.get(id); }
    String precision(J.Annotation annotation) { return annotations.get(annotation.getId()).precision(); }

    // Preserve the original matching rules. Both planning and editing use these identities,
    // even after a declaration's target signature has been applied in another source file.
    static String fieldKey(JavaType.Variable variable) {
        return variable != null && variable.getOwner() instanceof JavaType.FullyQualified
                ? ((JavaType.FullyQualified) variable.getOwner()).getFullyQualifiedName() + "#" + variable.getName() : null;
    }
    static String methodKey(JavaType.Method method) { return method == null ? null : method.toString(); }
}
