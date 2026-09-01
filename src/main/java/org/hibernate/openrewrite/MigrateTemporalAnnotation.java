/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.openrewrite;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.ChangeType;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.RemoveAnnotationVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

import java.util.List;

/**
 * Replaces deprecated {@code @Temporal} annotations with {@code java.time} field types.
 *
 * <p>{@code @Temporal} and {@code TemporalType} were deprecated in Jakarta Persistence 3.2
 * in favour of the date/time types defined in {@link java.time}. The mapping is:
 * <ul>
 *   <li>{@code @Temporal(DATE)}      + {@code Date}/{@code Calendar} &rarr; {@code LocalDate}</li>
 *   <li>{@code @Temporal(TIME)}      + {@code Date}/{@code Calendar} &rarr; {@code LocalTime}</li>
 *   <li>{@code @Temporal(TIMESTAMP)} + {@code Date}/{@code Calendar} &rarr; {@code LocalDateTime}</li>
 * </ul>
 */
public class MigrateTemporalAnnotation extends Recipe {

    private static final String TEMPORAL_FQN      = "jakarta.persistence.Temporal";
    private static final String TEMPORAL_TYPE_FQN = "jakarta.persistence.TemporalType";
    private static final String DATE_FQN          = "java.util.Date";
    private static final String CALENDAR_FQN      = "java.util.Calendar";

    @Override
    public String getDisplayName() {
        return "Replace @Temporal with java.time field types";
    }

    @Override
    public String getDescription() {
        return "Replaces the deprecated @Temporal annotation (deprecated in Jakarta Persistence 3.2) "
                + "by changing the field or property type to the appropriate java.time type: "
                + "DATE -> LocalDate, TIME -> LocalTime, TIMESTAMP -> LocalDateTime.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(
                new UsesType<>(TEMPORAL_FQN, false),
                new JavaIsoVisitor<ExecutionContext>() {

                    @Override
                    public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations multiVariable,
                                                                            ExecutionContext ctx) {
                        J.VariableDeclarations mv = super.visitVariableDeclarations(multiVariable, ctx);

                        J.Annotation temporal = findTemporalAnnotation(mv.getLeadingAnnotations());
                        if (temporal == null) {
                            return mv;
                        }

                        String temporalValue = extractTemporalType(temporal);
                        if (temporalValue == null) {
                            return mv;
                        }

                        String currentTypeFqn = currentTypeFqn(mv);
                        if (!DATE_FQN.equals(currentTypeFqn) && !CALENDAR_FQN.equals(currentTypeFqn)) {
                            return mv;
                        }

                        String newTypeFqn = javaTimeType(temporalValue);
                        if (newTypeFqn == null) {
                            return mv;
                        }

                        // Remove @Temporal cleanly (handles spacing)
                        doAfterVisit(new RemoveAnnotationVisitor(new AnnotationMatcher("@" + TEMPORAL_FQN) {
                            @Override
                            public boolean matches(J.Annotation anno) {
                                return anno == temporal;
                            }
                        }));
                        maybeRemoveImport(TEMPORAL_FQN);
                        maybeRemoveImport(TEMPORAL_TYPE_FQN);

                        // Swap the field type
                        doAfterVisit(new ChangeType(currentTypeFqn, newTypeFqn, true).getVisitor());

                        return mv;
                    }

                    private J.Annotation findTemporalAnnotation(List<J.Annotation> annotations) {
                        for (J.Annotation a : annotations) {
                            if (TypeUtils.isOfClassType(a.getType(), TEMPORAL_FQN)) {
                                return a;
                            }
                        }
                        return null;
                    }

                    private String extractTemporalType(J.Annotation temporal) {
                        if (temporal.getArguments() == null || temporal.getArguments().isEmpty()) {
                            return null;
                        }
                        Expression arg = temporal.getArguments().get(0);
                        if (arg instanceof J.FieldAccess) {
                            return ((J.FieldAccess) arg).getName().getSimpleName();
                        }
                        if (arg instanceof J.Assignment) {
                            Expression val = ((J.Assignment) arg).getAssignment();
                            if (val instanceof J.FieldAccess) {
                                return ((J.FieldAccess) val).getName().getSimpleName();
                            }
                        }
                        return null;
                    }

                    private String currentTypeFqn(J.VariableDeclarations mv) {
                        if (mv.getTypeExpression() == null) {
                            return null;
                        }
                        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(
                                mv.getTypeExpression().getType());
                        return fq == null ? null : fq.getFullyQualifiedName();
                    }

                    private String javaTimeType(String temporalValue) {
                        switch (temporalValue) {
                            case "DATE":      return "java.time.LocalDate";
                            case "TIME":      return "java.time.LocalTime";
                            case "TIMESTAMP": return "java.time.LocalDateTime";
                            default:          return null;
                        }
                    }
                });
    }
}
