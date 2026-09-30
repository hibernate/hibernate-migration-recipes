package org.hibernate.migration.recipes.temporal;

import org.hibernate.migration.recipes.support.MigrationSupport;
import org.hibernate.migration.recipes.support.SourcePositions;

import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.tree.*;
import java.util.*;

/// Discovers properties and consumers, resolves blockers, and freezes accepted edits.
/// Mutable candidates and dependencies never escape this execution-local planner.
/// @author Steve Ebersole
final class TemporalConversionPlanner {
    private static final String TEMPORAL_FQN = "jakarta.persistence.Temporal";
    private static final String TEMPORAL_TYPE_FQN = "jakarta.persistence.TemporalType";
    private static final String DATE_FQN = "java.util.Date";
    private static final String CALENDAR_FQN = "java.util.Calendar";
    private final PlanningState state = new PlanningState();
    private final TemporalValueConversion.Policy policy;

    TemporalConversionPlanner(List<J.CompilationUnit> inputs, Map<UUID, SourcePositions> positions,
            TemporalValueConversion.Policy policy) {
        state.inputs.addAll(inputs);
        state.positions.putAll(positions);
        this.policy = policy;
    }

    /// Builds decisions from all original inputs before any source can be rewritten.
    TemporalConversionPlan build(ExecutionContext ctx) {
        state.inputs.sort(Comparator.comparing(cu -> cu.getSourcePath().toString()));
        discoverGetterMappings(ctx);
        boolean customTimezone = detectCustomTimezones(ctx);
        discoverProperties(ctx);
        discoverAccessors(ctx);
        collectNameConflicts(ctx);
        discoverAliases(ctx);
        classifyUsages(ctx);
        applyTimezoneBlockers(customTimezone);
        propagateRejections();
        return freeze(ctx);
    }

    /// Execution-local input and plans. No planning state is retained on the recipe.
    private static final class PlanningState {
        final Map<UUID, SourcePositions> positions = new HashMap<>();
        final List<J.CompilationUnit> inputs = new ArrayList<>();
        // These indexes describe the original sources. Tree IDs identify particular edits;
        // attributed field/method identities connect declarations with uses in other files.
        final Map<UUID, Candidate> annotations = new LinkedHashMap<>();
        final Map<String, Candidate> fields = new HashMap<>();
        // Only legacy expressions needing a value conversion appear here. Transfers between
        // converted properties deliberately have no entry, preventing a second conversion.
        final Map<UUID, Candidate> values = new HashMap<>();
        final Map<String, Accessor> accessors = new HashMap<>();
        final Map<JavaType.Variable, Candidate> locals = new HashMap<>();
        final Map<String, J.Annotation> getterAnnotations = new HashMap<>();
        final Set<String> duplicateGetterMappings = new HashSet<>();
        final Set<String> conflictingNames = new HashSet<>();
        // Transfers link properties in both directions: either both migrate or neither does.
        final Map<Candidate, Set<Candidate>> dependencies = new HashMap<>();
        final Map<UUID, Candidate> transfers = new HashMap<>();
    }

    /// One attribute's all-or-nothing conversion plan.
    private static final class Candidate {
        final J.Annotation annotation;
        final String target;
        final String source;
        String reason;
        String message;

        Candidate(J.Annotation annotation, String target, String source) {
            this.annotation = annotation;
            this.target = target;
            this.source = source;
        }

        void reject(String reason, String message) {
            if (this.reason == null) {
                this.reason = reason;
                this.message = message;
            }
        }
    }

    /// Original accessor identity and its coordinated property plan.
    private record Accessor(Candidate property, boolean getter, JavaType.Method originalMethod) {
    }

    private static String methodKey(JavaType.Method method) {
        return TemporalConversionPlan.methodKey(method);
    }

    private static Expression unwrap(Expression value) {
        while (value instanceof J.Parentheses) value = (Expression) ((J.Parentheses<?>) value).getTree();
        return value;
    }

    private static JavaType.Variable symbol(Expression value) {
        value = unwrap(value);
        return value instanceof J.Identifier ? ((J.Identifier) value).getFieldType()
                : value instanceof J.FieldAccess ? ((J.FieldAccess) value).getName().getFieldType() : null;
    }

    private static boolean ownField(Expression expression) {
        expression = unwrap(expression);
        if (expression instanceof J.Identifier) return true;
        if (!(expression instanceof J.FieldAccess)) return false;
        Expression receiver = unwrap(((J.FieldAccess) expression).getTarget());
        return receiver instanceof J.Identifier && "this".equals(((J.Identifier) receiver).getSimpleName());
    }

    private static String getterField(J.MethodDeclaration method) {
        if (!method.getSimpleName().startsWith("get") || method.getBody() == null
                || method.getBody().getStatements().size() != 1
                || method.getMethodType() == null || !method.getMethodType().getParameterTypes().isEmpty()) return null;
        Statement statement = method.getBody().getStatements().get(0);
        return statement instanceof J.Return && ((J.Return) statement).getExpression() != null
                && ownField(((J.Return) statement).getExpression()) ? fieldKey(symbol(((J.Return) statement).getExpression())) : null;
    }

    // Follow only the supported paths back to a property: field, direct getter, or known local
    // alias. An arbitrary expression returning Date/Calendar is still a legacy value producer.
    private Candidate origin(Expression expression) {
        expression = unwrap(expression);
        if (expression instanceof J.MethodInvocation) {
            Accessor accessor = state.accessors.get(methodKey(((J.MethodInvocation) expression).getMethodType()));
            return accessor != null && accessor.getter ? accessor.property : null;
        }
        JavaType.Variable variable = symbol(expression);
        Candidate field = state.fields.get(fieldKey(variable));
        return field != null ? field : state.locals.get(variable);
    }

    // Record the dependency even for an incompatible transfer, so rejecting one endpoint
    // will also prevent partial conversion of the other endpoint and its connected properties.
    private void transfer(Candidate from, Candidate to, Expression expression) {
        state.transfers.put(expression.getId(), to);
        state.dependencies.computeIfAbsent(from, k -> new HashSet<>()).add(to);
        state.dependencies.computeIfAbsent(to, k -> new HashSet<>()).add(from);
        if (!Objects.equals(from.target, to.target) || !Objects.equals(precision(from.annotation), precision(to.annotation))) {
            from.reject("INCOMPATIBLE_TEMPORAL_TRANSFER", "Connected properties have different temporal targets or precisions.");
        }
    }

    private static String fieldKey(JavaType.Variable variable) {
        return TemporalConversionPlan.fieldKey(variable);
    }

    private static String precision(J.Annotation annotation) {
        if (annotation.getArguments() == null || annotation.getArguments().size() != 1) return null;
        Expression value = annotation.getArguments().get(0);
        if (value instanceof J.Assignment) {
            J.Assignment a = (J.Assignment) value;
            if (!(a.getVariable() instanceof J.Identifier)
                    || !"value".equals(((J.Identifier) a.getVariable()).getSimpleName())) return null;
            value = a.getAssignment();
        }
        J.Identifier name = value instanceof J.FieldAccess ? ((J.FieldAccess) value).getName()
                : value instanceof J.Identifier ? (J.Identifier) value : null;
        return name != null && name.getFieldType() != null
                && TypeUtils.isOfClassType(name.getFieldType().getOwner(), TEMPORAL_TYPE_FQN)
                ? name.getSimpleName() : null;
    }

    private void discoverGetterMappings(ExecutionContext ctx) {
        // Find getter-based mappings first: their @Temporal annotations must participate in
        // field planning even when the backing field has no annotation of its own.
        for (J.CompilationUnit input : state.inputs) {
            new JavaIsoVisitor<ExecutionContext>() {
                @Override public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration m, ExecutionContext p) {
                    String field = getterField(m);
                    if (field != null) for (J.Annotation a : m.getLeadingAnnotations()) {
                        if (TypeUtils.isOfClassType(a.getType(), TEMPORAL_FQN)
                                && state.getterAnnotations.put(field, a) != null) state.duplicateGetterMappings.add(field);
                    }
                    return super.visitMethodDeclaration(m, p);
                }
            }.visit(input, ctx);
        }
    }

    private boolean detectCustomTimezones(ExecutionContext ctx) {
        // Custom rules cannot in general be represented by a ZoneId. Without alias/data-flow
        // state, conservatively block Calendar zone conversions across this input batch.
        final boolean[] customTimezone = {false};
        for (J.CompilationUnit input : state.inputs) {
            new JavaIsoVisitor<ExecutionContext>() {
                @Override public J.NewClass visitNewClass(J.NewClass n, ExecutionContext p) {
                    if (TypeUtils.isAssignableTo("java.util.TimeZone", n.getType())) customTimezone[0] = true;
                    return super.visitNewClass(n, p);
                }
                @Override public J.MethodInvocation visitMethodInvocation(J.MethodInvocation m, ExecutionContext p) {
                    if (m.getMethodType() != null
                            && TypeUtils.isAssignableTo("java.util.TimeZone", m.getMethodType().getDeclaringType())
                            && m.getSimpleName().startsWith("set")) customTimezone[0] = true;
                    return super.visitMethodInvocation(m, p);
                }
            }.visit(input, ctx);
        }
        return customTimezone[0];
    }

    private void discoverProperties(ExecutionContext ctx) {
        // Build one candidate per mapped attribute and validate its declaration/mapping.
        // Initializer plans are provisional; a later unsupported caller can reject the candidate.
        for (J.CompilationUnit cu : state.inputs) {
            new JavaIsoVisitor<ExecutionContext>() {
                @Override
                public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations vd, ExecutionContext p) {
                    List<J.Annotation> temporalAnnotations = new ArrayList<>(vd.getLeadingAnnotations());
                    if (vd.getVariables().size() == 1) {
                        J.Annotation propertyAnnotation = state.getterAnnotations.get(fieldKey(vd.getVariables().get(0).getVariableType()));
                        if (propertyAnnotation != null) temporalAnnotations.add(propertyAnnotation);
                    }
                    for (J.Annotation a : temporalAnnotations) {
                        if (!TypeUtils.isOfClassType(a.getType(), TEMPORAL_FQN)) continue;
                        String source = vd.getTypeExpression() == null ? null
                                : TypeUtils.asFullyQualified(vd.getTypeExpression().getType()) == null ? null
                                : TypeUtils.asFullyQualified(vd.getTypeExpression().getType()).getFullyQualifiedName();
                        Candidate c = new Candidate(a, policy.target(precision(a)), source);
                        state.annotations.put(a.getId(), c);
                        if (c.target == null || (!DATE_FQN.equals(source) && !CALENDAR_FQN.equals(source))
                                || vd.getVariables().size() != 1
                                || fieldKey(vd.getVariables().get(0).getVariableType()) == null
                                || !vd.getVariables().get(0).getDimensionsAfterName().isEmpty()
                                || vd.hasModifier(J.Modifier.Type.Static)) {
                            c.reject("UNSUPPORTED_TEMPORAL_ATTRIBUTE", "Expected a single scalar Date or Calendar instance field with an attributed temporal precision.");
                        }
                        if (vd.getVariables().size() == 1) {
                            String key = fieldKey(vd.getVariables().get(0).getVariableType());
                            if (key != null) {
                                if (state.duplicateGetterMappings.contains(key))
                                    c.reject("AMBIGUOUS_TEMPORAL_MAPPING", "Multiple annotated getters describe the same field.");
                                Candidate previous = state.fields.put(key, c);
                                if (previous != null) {
                                    previous.reject("AMBIGUOUS_TEMPORAL_MAPPING", "Multiple temporal annotations describe the same field.");
                                    c.reject("AMBIGUOUS_TEMPORAL_MAPPING", "Multiple temporal annotations describe the same field.");
                                }
                            }
                        }
                        for (J.Annotation other : vd.getLeadingAnnotations()) {
                            if (!TypeUtils.isOfClassType(other.getType(), TEMPORAL_FQN)
                                    && !TypeUtils.isOfClassType(other.getType(), "jakarta.persistence.Basic")
                                    && !TypeUtils.isOfClassType(other.getType(), "jakarta.persistence.Column")) {
                                c.reject("TEMPORAL_MAPPING_REQUIRES_REVIEW", "Additional mapping or code-generation annotations require manual review.");
                            }
                        }
                        J.ClassDeclaration owner = getCursor().firstEnclosing(J.ClassDeclaration.class);
                        if (owner != null) {
                            String key = vd.getVariables().size() == 1 ? fieldKey(vd.getVariables().get(0).getVariableType()) : null;
                            for (J.Annotation access : owner.getLeadingAnnotations()) {
                                if (TypeUtils.isOfClassType(access.getType(), "jakarta.persistence.Access")) {
                                    String expected = state.getterAnnotations.containsKey(key) ? "PROPERTY" : "FIELD";
                                    if (access.getArguments() == null || access.getArguments().size() != 1
                                            || !access.getArguments().get(0).toString().endsWith(expected))
                                        c.reject("TEMPORAL_MAPPING_REQUIRES_REVIEW", "Explicit access conflicts with temporal mapping location.");
                                }
                            }
                            for (J.Annotation other : owner.getLeadingAnnotations()) {
                                JavaType.FullyQualified type = TypeUtils.asFullyQualified(other.getType());
                                if (type != null && (type.getFullyQualifiedName().startsWith("lombok.")
                                        || type.getFullyQualifiedName().startsWith("org.hibernate.annotations."))) {
                                    c.reject("TEMPORAL_MAPPING_REQUIRES_REVIEW", "Class-level mapping or generated accessors require manual review.");
                                }
                            }
                        }
                        if (c.reason == null && vd.getVariables().get(0).getInitializer() != null) {
                            valuePlan(c, vd.getVariables().get(0).getInitializer());
                        }
                    }
                    return super.visitVariableDeclarations(vd, p);
                }
            }.visit(cu, ctx);
        }
    }

    private void discoverAliases(ExecutionContext ctx) {
        // Discover aliases to a fixed point before validating their consumers.
        // Each pass may discover another link in "a = getter(); b = a; c = b".
        // Stop when no new aliases are found; this phase does not edit their declarations.
        boolean changed;
        do {
            int before = state.locals.size();
            for (J.CompilationUnit cu : state.inputs) new JavaIsoVisitor<ExecutionContext>() {
                @Override public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations vd, ExecutionContext p) {
                    if (vd.getVariables().size() == 1) {
                        J.VariableDeclarations.NamedVariable v = vd.getVariables().get(0);
                        if (fieldKey(v.getVariableType()) == null && v.getInitializer() != null) {
                            Candidate c = origin(v.getInitializer());
                            if (c != null && (TypeUtils.isOfClassType(v.getType(), c.source)
                                    || vd.getTypeExpression() instanceof J.Identifier && "var".equals(((J.Identifier) vd.getTypeExpression()).getSimpleName())))
                                state.locals.put(v.getVariableType(), c);
                        }
                    }
                    return super.visitVariableDeclarations(vd, p);
                }
            }.visit(cu, ctx);
            changed = before != state.locals.size();
        } while (changed);
    }

    private void classifyUsages(ExecutionContext ctx) {
        // Classify every use of a candidate or alias. The permitted contexts below are an
        // explicit allowlist: compilation alone cannot establish equivalent temporal semantics.
        for (J.CompilationUnit cu : state.inputs) {
            new JavaIsoVisitor<ExecutionContext>() {
                private void read(Expression expression, Candidate c) {
                    if (c == null) return;
                    if (state.conflictingNames.contains(cu.getSourcePath().toString()))
                        c.reject("NAME_RESOLUTION_CONFLICT", "A declaration named java conflicts with conversion in " + cu.getSourcePath());
                    if (getCursor().firstEnclosing(J.Lambda.class) != null) {
                        c.reject("UNSUPPORTED_TEMPORAL_USAGE", "Lambda capture in " + cu.getSourcePath());
                        return;
                    }
                    J.MethodDeclaration enclosing = getCursor().firstEnclosing(J.MethodDeclaration.class);
                    // Discovery has already checked direct accessor bodies. Their field/parameter
                    // types change together, so their internal reads need no boundary conversion.
                    if (enclosing != null && state.accessors.containsKey(methodKey(enclosing.getMethodType()))) return;
                    Cursor parent = getCursor().getParentTreeCursor();
                    while (parent.getValue() instanceof J.Parentheses) parent = parent.getParentTreeCursor();
                    Object use = parent.getValue();
                    if (use instanceof J.VariableDeclarations.NamedVariable) {
                        J.VariableDeclarations.NamedVariable variable = (J.VariableDeclarations.NamedVariable) use;
                        if (variable.getName().getId().equals(expression.getId())) return;
                        if (state.locals.get(variable.getVariableType()) == c) return;
                    }
                    if (use instanceof J.Binary) {
                        J.Binary b = (J.Binary) use;
                        if ((b.getOperator() == J.Binary.Type.Equal || b.getOperator() == J.Binary.Type.NotEqual)
                                && (nullValue(b.getLeft()) || nullValue(b.getRight()))) return;
                    }
                    if (use instanceof J.Assignment) {
                        J.Assignment a = (J.Assignment) use;
                        if (parent.getParentTreeCursor().getValue() instanceof J.Block) {
                            Candidate destination = state.fields.get(fieldKey(symbol(a.getVariable())));
                            if (destination != null) {
                                if (unwrap(a.getVariable()).getId().equals(expression.getId())) {
                                    Candidate source = origin(a.getAssignment());
                                    if (source != null) transfer(source, destination, a.getAssignment());
                                    else valuePlan(destination, a.getAssignment());
                                }
                                else transfer(c, destination, a.getAssignment());
                                return;
                            }
                        }
                    }
                    if (use instanceof J.MethodInvocation) {
                        J.MethodInvocation call = (J.MethodInvocation) use;
                        Accessor setter = state.accessors.get(methodKey(call.getMethodType()));
                        if (setter != null && !setter.getter && call.getArguments().size() == 1
                                && unwrap(call.getArguments().get(0)).getId().equals(expression.getId())) {
                            transfer(c, setter.property, call.getArguments().get(0));
                            return;
                        }
                    }
                    if (expression instanceof J.MethodInvocation && use instanceof J.Block) return;
                    c.reject("UNSUPPORTED_TEMPORAL_USAGE", "Unsupported property consumer at " + cu.getSourcePath() + ":" + state.positions.get(cu.getId()).location(expression.getId())
                            + ": " + expression.printTrimmed(getCursor()) + "; the entire property is unchanged.");
                }
                @Override public J.Annotation visitAnnotation(J.Annotation a, ExecutionContext p) {
                    Candidate c = state.annotations.get(a.getId());
                    if (c != null && state.conflictingNames.contains(cu.getSourcePath().toString()))
                        c.reject("NAME_RESOLUTION_CONFLICT", "A declaration named java conflicts with property conversion in " + cu.getSourcePath());
                    return super.visitAnnotation(a, p);
                }
                @Override public J.Identifier visitIdentifier(J.Identifier id, ExecutionContext p) {
                    Object parent = getCursor().getParentTreeCursor().getValue();
                    if (!(parent instanceof J.FieldAccess) || !((J.FieldAccess) parent).getName().getId().equals(id.getId()))
                        read(id, origin(id));
                    return super.visitIdentifier(id, p);
                }
                @Override public J.FieldAccess visitFieldAccess(J.FieldAccess f, ExecutionContext p) {
                    read(f, origin(f));
                    return super.visitFieldAccess(f, p);
                }
                @Override public J.MethodInvocation visitMethodInvocation(J.MethodInvocation m, ExecutionContext p) {
                    Accessor a = state.accessors.get(methodKey(m.getMethodType()));
                    if (a != null) {
                        if (state.conflictingNames.contains(cu.getSourcePath().toString()))
                            a.property.reject("NAME_RESOLUTION_CONFLICT", "A declaration named java conflicts with caller conversion in " + cu.getSourcePath());
                        if (a.getter) read(m, a.property);
                        else if (m.getArguments().size() == 1) {
                            Candidate source = origin(m.getArguments().get(0));
                            if (source != null) transfer(source, a.property, m.getArguments().get(0));
                            else valuePlan(a.property, m.getArguments().get(0));
                        }
                    }
                    return super.visitMethodInvocation(m, p);
                }
                @Override public J.MemberReference visitMemberReference(J.MemberReference m, ExecutionContext p) {
                    Accessor a = state.accessors.get(methodKey(m.getMethodType()));
                    if (a != null) a.property.reject("UNSUPPORTED_TEMPORAL_USAGE", "Method reference in " + cu.getSourcePath());
                    return super.visitMemberReference(m, p);
                }
            }.visit(cu, ctx);
        }
    }

    private void applyTimezoneBlockers(boolean customTimezone) {
        if (customTimezone && !Boolean.FALSE.equals(policy.honorCalendarTimeZone())) {
            for (Candidate c : state.values.values()) {
                if (CALENDAR_FQN.equals(c.source) && !"java.time.Instant".equals(c.target)) {
                    c.reject("UNSUPPORTED_CALENDAR_TIMEZONE",
                            "Custom timezone construction or mutation is present in supplied sources; Calendar zone conversion requires manual review.");
                }
            }
        }
    }

    private void propagateRejections() {
        boolean changed;
        // Finish all rejection decisions before editing. Repeat until a blocker has reached
        // every connected property, including transfers forming a cycle or spanning files.
        do {
            changed = false;
            for (Map.Entry<Candidate, Set<Candidate>> entry : state.dependencies.entrySet()) {
                if (entry.getKey().reason != null) for (Candidate dependent : entry.getValue()) {
                    if (dependent.reason == null) {
                        dependent.reject("CONNECTED_TEMPORAL_PROPERTY_BLOCKED", "A connected property cannot be converted safely.");
                        changed = true;
                    }
                }
            }
        } while (changed);    }


    /// Records files where generated java.* names could resolve to a source symbol instead.
    private void collectNameConflicts(ExecutionContext ctx) {
        Map<String, JavaType.FullyQualified> namedTypes = new HashMap<>();
        for (J.CompilationUnit cu : state.inputs) new JavaIsoVisitor<ExecutionContext>() {
            @Override public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration c, ExecutionContext p) {
                if ("java".equals(c.getSimpleName()) && c.getType() != null)
                    namedTypes.put(c.getType().getFullyQualifiedName().replace('$', '.'), c.getType());
                return super.visitClassDeclaration(c, p);
            }
        }.visit(cu, ctx);
        for (J.CompilationUnit cu : state.inputs) {
            final boolean[] conflict = {false};
            String packageName = cu.getPackageDeclaration() == null ? "" : cu.getPackageDeclaration().getExpression().printTrimmed();
            for (JavaType.FullyQualified type : namedTypes.values()) {
                if (type.getOwningClass() == null && type.getPackageName().equals(packageName)) conflict[0] = true;
            }
            for (J.Import imported : cu.getImports()) {
                String name = imported.getQualid().getName().getSimpleName();
                if ("java".equals(name)) conflict[0] = true;
                // Wildcard imports can expose a type declared in another supplied file.
                if ("*".equals(name)) {
                    String owner = imported.getQualid().getTarget().printTrimmed();
                    if (namedTypes.containsKey(owner + ".java")) conflict[0] = true;
                    JavaType.FullyQualified importedType = TypeUtils.asFullyQualified(imported.getQualid().getTarget().getType());
                    if (imported.isStatic() && importedType != null && hasJavaMember(importedType)) conflict[0] = true;
                }
            }
            new JavaIsoVisitor<ExecutionContext>() {
                @Override public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration c, ExecutionContext p) {
                    if ("java".equals(c.getSimpleName()) || c.getType() != null && hasJavaMember(c.getType())) conflict[0] = true;
                    // Nested types are not included in getVisibleMembers(). Check their supplied
                    // declarations separately, including types inherited from an ancestor.
                    for (JavaType.FullyQualified type : namedTypes.values()) {
                        if (type.getOwningClass() != null && !type.hasFlags(Flag.Private)
                                && TypeUtils.isAssignableTo(type.getOwningClass().getFullyQualifiedName(), c.getType())) conflict[0] = true;
                    }
                    return super.visitClassDeclaration(c, p);
                }
                @Override public J.TypeParameter visitTypeParameter(J.TypeParameter t, ExecutionContext p) {
                    if (t.getName() instanceof J.Identifier && "java".equals(((J.Identifier) t.getName()).getSimpleName())) conflict[0] = true;
                    return super.visitTypeParameter(t, p);
                }
                @Override public J.VariableDeclarations.NamedVariable visitVariable(J.VariableDeclarations.NamedVariable v, ExecutionContext p) {
                    if ("java".equals(v.getSimpleName())) conflict[0] = true;
                    return super.visitVariable(v, p);
                }
            }.visit(cu, ctx);
            if (conflict[0]) state.conflictingNames.add(cu.getSourcePath().toString());
        }
    }

    private static boolean hasJavaMember(JavaType.FullyQualified type) {
        Iterator<JavaType.Variable> members = type.getVisibleMembers();
        while (members.hasNext()) {
            JavaType.Variable member = members.next();
            JavaType.FullyQualified owner = TypeUtils.asFullyQualified(member.getOwner());
            if ("java".equals(member.getName()) && owner != null && visibleMember(member.getFlags(), owner, type)) return true;
        }
        return false;
    }

    private static boolean visibleMember(Set<Flag> flags, JavaType.FullyQualified owner, JavaType.FullyQualified from) {
        // The Rewrite iterator can include inaccessible ancestor members; apply visibility
        // explicitly so a private ancestor overload does not block an otherwise safe change.
        return owner.getFullyQualifiedName().equals(from.getFullyQualifiedName())
                || !flags.contains(Flag.Private) && (flags.contains(Flag.Public) || flags.contains(Flag.Protected)
                || owner.getPackageName().equals(from.getPackageName()));
    }

    /// Changing a setter parameter can create an override that did not exist in the source.
    /// Follow the existing conservative overload policy across the visible hierarchy as well.
    private static boolean hasInheritedAccessorName(JavaType.Method method) {
        if (method == null) return false;
        JavaType.FullyQualified owner = method.getDeclaringType();
        Iterator<JavaType.Method> methods = owner.getVisibleMethods();
        while (methods.hasNext()) {
            JavaType.Method inherited = methods.next();
            if (inherited.getName().equals(method.getName())
                    && !inherited.getDeclaringType().getFullyQualifiedName().equals(owner.getFullyQualifiedName())
                    && visibleMember(inherited.getFlags(), inherited.getDeclaringType(), owner)) return true;
        }
        return false;
    }

    private void discoverAccessors(ExecutionContext ctx) {
        for (J.CompilationUnit cu : state.inputs) new JavaIsoVisitor<ExecutionContext>() {
            @Override public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration m, ExecutionContext p) {
                Candidate c = state.fields.get(getterField(m));
                boolean getter = c != null;
                if (c == null && m.getSimpleName().startsWith("set") && m.getBody() != null
                        && m.getBody().getStatements().size() == 1 && m.getParameters().size() == 1
                        && m.getParameters().get(0) instanceof J.VariableDeclarations
                        && m.getBody().getStatements().get(0) instanceof J.Assignment) {
                    J.Assignment assignment = (J.Assignment) m.getBody().getStatements().get(0);
                    J.VariableDeclarations parameter = (J.VariableDeclarations) m.getParameters().get(0);
                    if (parameter.getVariables().size() == 1 && ownField(assignment.getVariable()) && Objects.equals(symbol(assignment.getAssignment()), parameter.getVariables().get(0).getVariableType())
                            && m.getMethodType() != null && m.getMethodType().getReturnType() == JavaType.Primitive.Void)
                        c = state.fields.get(fieldKey(symbol(assignment.getVariable())));
                }
                // A bean-shaped method that failed the direct-body check must block its
                // property, rather than accidentally being treated as an ordinary legacy caller.
                if (c == null && (m.getSimpleName().startsWith("get") || m.getSimpleName().startsWith("set"))) {
                    J.ClassDeclaration owner = getCursor().firstEnclosing(J.ClassDeclaration.class);
                    if (owner != null && owner.getType() != null) {
                        String prefix = owner.getType().getFullyQualifiedName() + "#";
                        for (Map.Entry<String, Candidate> field : state.fields.entrySet()) {
                            if (field.getKey().startsWith(prefix)) {
                                String name = field.getKey().substring(prefix.length());
                                String beanName = Character.toUpperCase(name.charAt(0)) + name.substring(1);
                                if (m.getSimpleName().substring(3).equals(beanName))
                                    field.getValue().reject("UNSUPPORTED_TEMPORAL_ACCESSOR", "Complex or incompatible accessor " + m.getSimpleName() + " requires review.");
                            }
                        }
                    }
                }
                if (c != null) {
                    JavaType.Method type = m.getMethodType();
                    if (type == null || !TypeUtils.isOfClassType(getter ? type.getReturnType() : type.getParameterTypes().get(0), c.source)
                            || m.getTypeParameters() != null && !m.getTypeParameters().isEmpty()) c.reject("UNSUPPORTED_TEMPORAL_ACCESSOR", "Accessor types must match the backing field.");
                    J.ClassDeclaration owner = getCursor().firstEnclosing(J.ClassDeclaration.class);
                    if (owner != null && owner.getType() != null) {
                        JavaType.FullyQualified declaring = owner.getType();
                        if (TypeUtils.isOverride(type) || hasInheritedAccessorName(type))
                            c.reject("UNSUPPORTED_TEMPORAL_HIERARCHY", "Accessor hierarchy requires review.");
                        long names = declaring.getMethods().stream().filter(it -> it.getName().equals(m.getSimpleName())).count();
                        if (names > 1) c.reject("UNSUPPORTED_TEMPORAL_OVERLOAD", "Overloaded accessor requires review.");
                    }
                    String field = getter ? getterField(m) : fieldKey(symbol(((J.Assignment) m.getBody().getStatements().get(0)).getVariable()));
                    if (type == null || field == null || !field.startsWith(type.getDeclaringType().getFullyQualifiedName() + "#")
                            || m.hasModifier(J.Modifier.Type.Static))
                        c.reject("UNSUPPORTED_TEMPORAL_ACCESSOR", "Accessor must directly access its own instance field.");
                    for (J.Annotation a : m.getLeadingAnnotations()) {
                        if (!TypeUtils.isOfClassType(a.getType(), TEMPORAL_FQN)
                                && !TypeUtils.isOfClassType(a.getType(), "jakarta.persistence.Basic")
                                && !TypeUtils.isOfClassType(a.getType(), "jakarta.persistence.Column"))
                            c.reject("TEMPORAL_MAPPING_REQUIRES_REVIEW", "Additional accessor annotations require review.");
                    }
                    if (owner != null) for (J.Annotation a : owner.getLeadingAnnotations()) {
                        if (TypeUtils.isOfClassType(a.getType(), "jakarta.persistence.Access")) {
                            String expected = state.getterAnnotations.containsKey(field) ? "PROPERTY" : "FIELD";
                            if (a.getArguments() == null || a.getArguments().size() != 1
                                    || !a.getArguments().get(0).toString().endsWith(expected))
                                c.reject("TEMPORAL_MAPPING_REQUIRES_REVIEW", "Explicit access does not match the temporal mapping location.");
                        }
                    }
                    state.accessors.put(methodKey(type), new Accessor(c, getter, type));
                    if (!getter) {
                        J.VariableDeclarations param = (J.VariableDeclarations) m.getParameters().get(0);
                        state.locals.put(param.getVariables().get(0).getVariableType(), c);
                    }
                }
                return super.visitMethodDeclaration(m, p);
            }
        }.visit(cu, ctx);
        // Also check the reverse hierarchy direction: a supported base accessor cannot change
        // signature while a supplied subclass still overrides its original legacy signature.
        for (J.CompilationUnit cu : state.inputs) new JavaIsoVisitor<ExecutionContext>() {
            @Override public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration m, ExecutionContext p) {
                JavaType.Method type = m.getMethodType();
                if (type != null) {
                    // A descendant overload may become an override after conversion, even
                    // though TypeUtils.isOverride() is false for its original signature.
                    for (Accessor accessor : state.accessors.values()) {
                        JavaType.Method base = accessor.originalMethod;
                        if (base != null && type.getName().equals(base.getName())
                                && !type.getDeclaringType().getFullyQualifiedName().equals(base.getDeclaringType().getFullyQualifiedName())
                                && TypeUtils.isAssignableTo(base.getDeclaringType().getFullyQualifiedName(), type.getDeclaringType()))
                            accessor.property.reject("UNSUPPORTED_TEMPORAL_HIERARCHY", "Accessor name is also declared in a supplied subclass.");
                    }
                }
                return super.visitMethodDeclaration(m, p);
            }
        }.visit(cu, ctx);
    }

    private static boolean nullValue(Expression value) {
        value = unwrap(value);
        return value instanceof J.Literal && ((J.Literal) value).getValue() == null;
    }

    private void valuePlan(Candidate c, Expression value) {
        // Settings are required only at an actual legacy-value boundary. A declaration or
        // null assignment needs no timezone, and rejected candidates acquire no further edits.
        if (c.reason != null || nullValue(value)) return;
        if (!TypeUtils.isAssignableTo(c.source, value.getType())) {
            c.reject("UNSUPPORTED_TEMPORAL_VALUE", "Cannot establish the source value's temporal type.");
            return;
        }
        boolean calendar = CALENDAR_FQN.equals(c.source);
        if (!"java.time.Instant".equals(c.target) && !(calendar && !Boolean.FALSE.equals(policy.honorCalendarTimeZone()))
                && policy.configuredZone(c.target) == null) {
            c.reject("TEMPORAL_TIMEZONE_REQUIRED", "Configure the timezone source required by this value conversion.");
            return;
        }
        state.values.put(value.getId(), c);
    }


    /// Copies final decisions rather than exposing the mutable candidate objects behind indexes.
    private TemporalConversionPlan freeze(ExecutionContext ctx) {
        Map<Candidate, TemporalConversionPlan.Property> decisions = new IdentityHashMap<>();
        for (Candidate c : state.annotations.values()) {
            TemporalConversionPlan.Diagnostic diagnostic = c.reason == null ? null
                    : new TemporalConversionPlan.Diagnostic("Temporal", c.reason, c.message);
            decisions.put(c, new TemporalConversionPlan.Property(c.annotation, c.source, c.target, precision(c.annotation), diagnostic));
        }
        Map<UUID, TemporalConversionPlan.Property> annotations = new LinkedHashMap<>();
        state.annotations.forEach((id, candidate) -> annotations.put(id, decisions.get(candidate)));
        // Unsupported locations and unresolved annotation types used to be reported by the
        // editing visitor. Record those decisions here as well so editing remains mechanical.
        for (J.CompilationUnit cu : state.inputs) new JavaIsoVisitor<ExecutionContext>() {
            @Override public J.Annotation visitAnnotation(J.Annotation a, ExecutionContext p) {
                if (!annotations.containsKey(a.getId())) {
                    TemporalConversionPlan.Diagnostic diagnostic = null;
                    if (TypeUtils.isOfClassType(a.getType(), TEMPORAL_FQN))
                        diagnostic = new TemporalConversionPlan.Diagnostic("Temporal", "UNSUPPORTED_TEMPORAL_ATTRIBUTE",
                                "Expected an annotated scalar field or a direct getter with a unique backing field.");
                    else if ((a.getType() == null || a.getType() instanceof JavaType.Unknown)
                            && MigrationSupport.explicitType(a, TEMPORAL_FQN, cu))
                        diagnostic = new TemporalConversionPlan.Diagnostic(TEMPORAL_FQN, "MISSING_TYPE_ATTRIBUTION",
                                "Resolve the source JPA API before migrating this annotation.");
                    if (diagnostic != null) annotations.put(a.getId(), new TemporalConversionPlan.Property(a, null, null, null, diagnostic));
                }
                return super.visitAnnotation(a, p);
            }
        }.visit(cu, ctx);
        Map<String, TemporalConversionPlan.Property> fields = new HashMap<>();
        state.fields.forEach((key, c) -> { if (c.reason == null) fields.put(key, decisions.get(c)); });
        Map<JavaType.Variable, TemporalConversionPlan.Property> locals = new HashMap<>();
        state.locals.forEach((key, c) -> { if (c.reason == null) locals.put(key, decisions.get(c)); });
        Map<String, TemporalConversionPlan.Accessor> accessors = new HashMap<>();
        state.accessors.forEach((key, a) -> {
            if (a.property.reason == null) accessors.put(key, new TemporalConversionPlan.Accessor(decisions.get(a.property), a.getter, a.originalMethod));
        });
        Map<UUID, TemporalConversionPlan.ValueAction> values = new HashMap<>();
        state.values.forEach((id, c) -> {
            if (c.reason == null) values.put(id, TemporalConversionPlan.ValueAction.convert(policy.conversion(c.source, c.target)));
        });
        state.transfers.forEach((id, c) -> {
            if (c.reason == null) values.putIfAbsent(id, TemporalConversionPlan.ValueAction.passThrough());
        });
        return new TemporalConversionPlan(annotations, fields, locals, accessors, values);
    }
}
