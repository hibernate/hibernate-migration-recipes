/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.migration.recipes;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.TypeUtils;

import java.util.List;

/**
 * Renames the deprecated {@code name} attribute of {@code @MapKey} to {@code value}.
 *
 * <p>In Jakarta Persistence 4.0 the {@code name} member of {@code @MapKey} was deprecated
 * in favour of the conventional {@code value} attribute. Both have identical semantics.
 *
 * <pre>
 * // before
 * {@literal @}MapKey(name = "empId")
 * Map&lt;Integer, Employee&gt; employees;
 *
 * // after
 * {@literal @}MapKey("empId")
 * Map&lt;Integer, Employee&gt; employees;
 * </pre>
 */
public class MigrateMapKeyNameToValue extends Recipe {

    private static final String MAP_KEY_FQN = "jakarta.persistence.MapKey";

    @Override
    public String getDisplayName() {
        return "Replace @MapKey(name=...) with @MapKey(value=...) or @MapKey(\"...\")";
    }

    @Override
    public String getDescription() {
        return "Renames the deprecated name attribute of @MapKey to value, as introduced in Jakarta Persistence 4.0.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(
                new UsesType<>(MAP_KEY_FQN, false),
                new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.Annotation visitAnnotation(J.Annotation annotation, ExecutionContext ctx) {
                        J.Annotation ann = super.visitAnnotation(annotation, ctx);
                        if (!TypeUtils.isOfClassType(ann.getType(), MAP_KEY_FQN)) {
                            return ann;
                        }
                        if (ann.getArguments() == null || ann.getArguments().isEmpty()) {
                            return ann;
                        }

                        List<Expression> newArgs = ListUtils.map(ann.getArguments(), arg -> {
                            if (!(arg instanceof J.Assignment)) {
                                return arg;
                            }
                            J.Assignment assignment = (J.Assignment) arg;
                            if (!(assignment.getVariable() instanceof J.Identifier)) {
                                return arg;
                            }
                            J.Identifier key = (J.Identifier) assignment.getVariable();
                            if (!"name".equals(key.getSimpleName())) {
                                return arg;
                            }
                            // Single-attribute annotation  simplify to @MapKey("value")
                            if (ann.getArguments().size() == 1) {
                                return assignment.getAssignment().withPrefix(assignment.getPrefix());
                            }
                            // Multiple attributes  rename name -> value
                            return assignment.withVariable(key.withSimpleName("value"));
                        });

                        if (newArgs == ann.getArguments()) {
                            return ann;
                        }
                        return ann.withArguments(newArgs);
                    }
                });
    }
}
