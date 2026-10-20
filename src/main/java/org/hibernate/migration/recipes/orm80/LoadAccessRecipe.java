package org.hibernate.migration.recipes.orm80;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.hibernate.migration.recipes.support.MigrationSupport;
import org.hibernate.migration.recipes.table.SkippedMigrations;
import org.jspecify.annotations.NonNull;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

/// Plans and atomically replaces directly completed Hibernate load-access chains.
/// Target API types are constructed explicitly, keeping Hibernate off the recipe classpath.
///
/// @author Steve Ebersole
abstract class LoadAccessRecipe extends ScanningRecipe<Set<String>> {
    private final String factory;
    private final String access;
    private final boolean multiple;
    private final boolean natural;
    private final transient SkippedMigrations skipped = new SkippedMigrations(this);

    LoadAccessRecipe(String factory, String access, boolean multiple, boolean natural) {
        this.factory = factory;
        this.access = "org.hibernate." + access;
        this.multiple = multiple;
        this.natural = natural;
    }

    @Override
    public @NonNull String getDisplayName() { return "Migrate Session." + factory + " loading"; }

    @Override
    public @NonNull String getDescription() {
        return "Replaces supported completed Session." + factory + " chains with find operations, preserving legacy defaults and reporting unsupported candidates.";
    }

    @Override
    public Set<String> getInitialValue(ExecutionContext ctx) { return new HashSet<>(); }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Set<String> packageShadows) {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration cd, ExecutionContext ctx) {
                if (Set.of("java", "org", "jakarta").contains(cd.getSimpleName()) && cd.getType() != null) {
                    JavaType.FullyQualified owner = cd.getType().getOwningClass();
                    packageShadows.add(cd.getSimpleName() + ":" + (owner == null
                            ? "package:" + cd.getType().getPackageName() : owner.getFullyQualifiedName()));
                }
                return super.visitClassDeclaration(cd, ctx);
            }
        };
    }

    @Override
    public @NonNull TreeVisitor<?, ExecutionContext> getVisitor(Set<String> packageShadows) {
        return new MigrationSupport.JavaVisitor(this, skipped) {
            @Override
            public J.@NonNull MemberReference visitMemberReference(J.@NonNull MemberReference ref, @NonNull ExecutionContext ctx) {
                J.MemberReference r = super.visitMemberReference(ref, ctx);
                if (factory.equals(r.getReference().getSimpleName()) && sessionMethod(r.getMethodType())) {
                    skip(r, ctx, factory, "LOAD_ACCESS_ESCAPED_BUILDER", "Migrate the loader factory method reference manually.");
                }
                return r;
            }

            @Override
            public J.@NonNull MethodInvocation visitMethodInvocation(J.@NonNull MethodInvocation original, @NonNull ExecutionContext ctx) {
                J.MethodInvocation call = super.visitMethodInvocation(original, ctx);
                List<J.MethodInvocation> chain = chain(call);
                if (chain.isEmpty() || hasBuilderParent(call)) return call;
                J.MethodInvocation root = chain.get(0);
                Plan plan = plan(chain);
                if (plan.reason != null) {
                    skip(root, ctx, factory, plan.reason, plan.message);
                    return call;
                }
                Expression key = plan.key;
                if (plan.namedPair != null) {
                    key = staticCall("java.util.Collections", "singletonMap", plan.namedPair,
                            parameterized("java.util.Map", JavaType.Primitive.String, objectType()));
                }
                else if (plan.varargs) {
                    key = staticCall("java.util.List", "of", arguments(call), parameterized("java.util.List", objectType()))
                            .withTypeParameters(call.getPadding().getTypeParameters());
                }
                List<Expression> args = new ArrayList<>();
                args.add(arguments(root).get(0));
                args.add(key);
                args.addAll(plan.options);
                JavaType resultType = call.getType();
                if (call.getSimpleName().equals("loadOptional")) {
                    if (!(resultType instanceof JavaType.Parameterized p) || p.getTypeParameters().size() != 1) {
                        skip(root, ctx, factory, "LOAD_ACCESS_UNSUPPORTED_CONTEXT", "Resolve the optional result's entity type.");
                        return call;
                    }
                    resultType = p.getTypeParameters().get(0);
                }
                J.MethodInvocation changed = invocation(root.getSelect(), "org.hibernate.Session", multiple ? "findMultiple" : "find",
                        args, resultType, false).withTypeParameters(root.getPadding().getTypeParameters());
                if (call.getSimpleName().equals("loadOptional")) {
                    changed = staticCall("java.util.Optional", "ofNullable", List.of(changed), call.getType());
                }
                // Reuse the terminal ID so enclosing expressions retain their identity.
                changed = changed.withId(call.getId()).withPrefix(call.getPrefix());
                return changed;
            }

            private boolean hasBuilderParent(J.MethodInvocation call) {
                Cursor parent = getCursor().getParent();
                while (parent != null && (parent.getValue() instanceof JRightPadded<?> || parent.getValue() instanceof J.Parentheses<?>)) {
                    parent = parent.getParent();
                }
                return parent != null && parent.getValue() instanceof J.MethodInvocation next
                        && unwrap(next.getSelect()) == call && TypeUtils.isOfClassType(call.getType(), access);
            }

            private List<J.MethodInvocation> chain(J.MethodInvocation terminal) {
                List<J.MethodInvocation> result = new ArrayList<>();
                J.MethodInvocation current = terminal;
                while (true) {
                    result.add(current);
                    if (factory.equals(current.getSimpleName()) && (sessionMethod(current.getMethodType())
                            || current.getMethodType() == null && current.getSelect() != null
                            && TypeUtils.isAssignableTo("org.hibernate.Session", current.getSelect().getType()))) {
                        Collections.reverse(result);
                        return result;
                    }
                    Expression select = unwrap(current.getSelect());
                    if (!(select instanceof J.MethodInvocation previous) || !TypeUtils.isOfClassType(previous.getType(), access)) {
                        return List.of();
                    }
                    current = previous;
                }
            }

            private Plan plan(List<J.MethodInvocation> chain) {
                Plan p = new Plan();
                J.MethodInvocation root = chain.get(0);
                J.MethodInvocation terminal = chain.get(chain.size() - 1);
                if (root.getMethodType() == null || root.getMethodType().getParameterTypes().size() != 1
                        || arguments(root).size() != 1 || arguments(root).get(0).getType() == null) {
                    return p.reject("UNRESOLVED_TYPE", "Resolve the Hibernate factory and entity type.");
                }
                for (J.MethodInvocation part : chain) {
                    if (part.getMethodType() == null || part.getType() == null) {
                        return p.reject("UNRESOLVED_TYPE", "Resolve all methods in the Hibernate loading chain.");
                    }
                }
                if (TypeUtils.isString(root.getMethodType().getParameterTypes().get(0))) {
                    return p.reject("ENTITY_NAME", "An entity name cannot safely be inferred as a Java Class.");
                }
                if (chain.size() == 1 || !Set.of("load", "loadOptional", "getReference", "multiLoad").contains(terminal.getSimpleName())) {
                    return p.reject("ESCAPED_BUILDER", "Only directly completed loading chains can be migrated.");
                }
                if (terminal.getSimpleName().equals("getReference")) {
                    return p.reject(natural ? "NATURAL_REFERENCE" : "IDENTIFIER_REFERENCE", natural
                            ? "Beta2's natural reference operation changes missing-key and synchronization behavior."
                            : "Beta2's identifier reference operation changes read-only behavior for eager references.");
                }
                if (!TypeUtils.isOfClassType(root.getMethodType().getDeclaringType(), "org.hibernate.Session")) {
                    return p.reject("UNSUPPORTED_CONTEXT", "An application override may have different loading semantics.");
                }
                List<Expression> keyExpressions = new ArrayList<>(arguments(terminal));
                List<Expression> optionExpressions = new ArrayList<>();
                List<Expression> directOptions = new ArrayList<>();
                Set<String> categories = new HashSet<>();
                List<J.MethodInvocation> usings = new ArrayList<>();
                boolean mode = false;
                boolean timeout = false;
                for (int i = 1; i < chain.size() - 1; i++) {
                    J.MethodInvocation setter = chain.get(i);
                    if (!TypeUtils.isOfClassType(setter.getMethodType().getDeclaringType(), access)) {
                        return p.reject("UNSUPPORTED_CONFIGURATION", "A custom loader method cannot be migrated.");
                    }
                    List<Expression> a = arguments(setter);
                    if (setter.getSimpleName().equals("using") && factory.equals("byNaturalId")) {
                        usings.add(setter);
                        continue;
                    }
                    String category = setter.getSimpleName();
                    if (category.equals("with")) {
                        category = setter.getMethodType().getParameterTypes().get(0).toString();
                    }
                    if (!categories.add(category)) return p.reject("UNSUPPORTED_CONFIGURATION", "Repeated loader setting: " + setter.getSimpleName());
                    switch (setter.getSimpleName()) {
                        case "with" -> {
                            String type = setter.getMethodType().getParameterTypes().get(0).toString();
                            if (!Set.of("org.hibernate.CacheMode", "org.hibernate.LockMode", "jakarta.persistence.Timeout").contains(type)
                                    || a.isEmpty()) {
                                return p.reject("UNSUPPORTED_CONFIGURATION", "Unsupported with option: " + type);
                            }
                            directOptions.addAll(a);
                            if (type.equals("org.hibernate.LockMode")) {
                                mode = true;
                                p.options.add(a.get(0));
                                if (a.size() == 2) {
                                    p.options.add(a.get(1));
                                }
                                else p.options.add(constant("jakarta.persistence.PessimisticLockScope", "NORMAL"));
                            }
                            else {
                                p.options.add(a.get(0));
                                timeout |= type.equals("jakarta.persistence.Timeout");
                            }
                        }
                        case "withReadOnly" -> p.options.add(booleanOption(a.get(0), "org.hibernate.ReadOnlyMode", "READ_ONLY", "READ_WRITE"));
                        case "setSynchronizationEnabled" -> p.options.add(booleanOption(a.get(0), "org.hibernate.NaturalIdSynchronization", "ENABLED", "DISABLED"));
                        case "enableSessionCheck" -> p.options.add(booleanOption(a.get(0), "org.hibernate.FindMultipleOption$SessionCheckMode", "ENABLED", "DISABLED"));
                        case "enableReturnOfDeletedEntities" -> p.options.add(booleanOption(a.get(0), "org.hibernate.FindMultipleOption$RemovalsMode", "INCLUDE", "REPLACE"));
                        case "enableOrderedReturn" -> p.options.add(booleanOption(a.get(0), "org.hibernate.FindMultipleOption$OrderingMode", "ORDERED", "UNORDERED"));
                        case "withBatchSize" -> {
                            if (!(a.get(0) instanceof J.Literal literal) || !(literal.getValue() instanceof Integer n) || n < 1) {
                                return p.reject("UNSUPPORTED_CONFIGURATION", "Only a positive literal batch size is supported.");
                            }
                            p.options.add(batch(a.get(0)));
                        }
                        default -> { return p.reject("UNSUPPORTED_CONFIGURATION", "Unsupported loader setting: " + setter.getSimpleName()); }
                    }
                    optionExpressions.addAll(a);
                }
                if (factory.equals("byNaturalId")) {
                    if (usings.size() != 1) return p.reject("UNSUPPORTED_NATURAL_KEY", "Use exactly one map or string/value natural-key mapping.");
                    J.MethodInvocation using = usings.get(0);
                    List<Expression> a = arguments(using);
                    if (a.size() == 1 && TypeUtils.isAssignableTo("java.util.Map", a.get(0).getType())) {
                        p.key = a.get(0);
                    }
                    else if (a.size() == 2 && TypeUtils.isString(using.getMethodType().getParameterTypes().get(0))) {
                        p.namedPair = a;
                    }
                    else return p.reject("UNSUPPORTED_NATURAL_KEY", "Metamodel natural-key mappings require manual migration.");
                    keyExpressions = a;
                    // Map input is copied at using(), not at the eventual terminal.
                    int usingIndex = chain.indexOf(using);
                    for (int i = usingIndex + 1; i < chain.size() - 1; i++) {
                        if (arguments(chain.get(i)).stream().anyMatch(e -> !inert(e))) {
                            return p.reject("EVALUATION_ORDER", "An option after using() could mutate the natural-key map or value.");
                        }
                    }
                }
                else if (multiple) {
                    boolean list = terminal.getMethodType().getParameterTypes().size() == 1
                            && TypeUtils.isAssignableTo("java.util.List", terminal.getMethodType().getParameterTypes().get(0));
                    p.varargs = !list;
                    if (list) {
                        if (keyExpressions.size() != 1) return p.reject("UNSUPPORTED_CONTEXT", "Resolve the bulk loading overload.");
                        p.key = keyExpressions.get(0);
                    }
                }
                else if (keyExpressions.size() == 1) p.key = keyExpressions.get(0);
                else return p.reject("UNSUPPORTED_CONTEXT", "Resolve the terminal key argument.");
                if (directOptions.stream().anyMatch(e -> !nonNullOption(e))) {
                    return p.reject("EVALUATION_ORDER", "A direct loading option might be null.");
                }
                if (optionExpressions.stream().anyMatch(e -> !inert(e)) && keyExpressions.stream().anyMatch(e -> !inert(e))) {
                    return p.reject("EVALUATION_ORDER", "Moving loading options past the key could change evaluation order.");
                }
                if (natural) p.options.add(0, constant("org.hibernate.KeyType", "NATURAL"));
                if (multiple && !natural) {
                    if (!categories.contains("enableSessionCheck")) p.options.add(constant("org.hibernate.FindMultipleOption$SessionCheckMode", "DISABLED"));
                    if (!categories.contains("withReadOnly")) p.options.add(constant("org.hibernate.ReadOnlyMode", "READ_WRITE"));
                }
                if (!mode) p.options.add(constant("org.hibernate.LockMode", "NONE"));
                else {
                    if (!timeout) p.options.add(constant("org.hibernate.Timeouts", "WAIT_FOREVER", type("jakarta.persistence.Timeout")));
                    p.options.add(constant("org.hibernate.Locking$FollowOn", "ALLOW"));
                }
                List<Expression> retained = new ArrayList<>();
                if (root.getSelect() != null) retained.add(root.getSelect());
                retained.addAll(arguments(root));
                retained.addAll(keyExpressions);
                retained.addAll(optionExpressions);
                List<Comment> kept = new ArrayList<>(terminal.getPrefix().getComments());
                for (Expression e : retained) kept.addAll(comments(e));
                for (Comment comment : comments(terminal)) {
                    if (!kept.remove(comment)) return p.reject("UNSUPPORTED_CONTEXT", "A comment on a discarded chain segment requires manual placement.");
                }
                if (explicitNull(arguments(root).get(0)) || p.key != null && explicitNull(p.key)
                        || p.namedPair != null && explicitNull(p.namedPair.get(0))
                        || p.varargs && keyExpressions.stream().anyMatch(LoadAccessRecipe::explicitNull)) {
                    return p.reject("UNSUPPORTED_CONTEXT", "An explicitly null entity type or key is not a supported loading input.");
                }
                JavaType.FullyQualified receiverType = TypeUtils.asFullyQualified(root.getSelect() == null
                        ? getCursor().firstEnclosing(J.ClassDeclaration.class).getType() : root.getSelect().getType());
                if (customFind(receiverType, new HashSet<>())) {
                    return p.reject("UNSUPPORTED_CONTEXT", "An application find overload or override could intercept the replacement call.");
                }
                if (shadowsPackage("org") || shadowsPackage("java") || shadowsPackage("jakarta")) {
                    return p.reject("UNSUPPORTED_CONTEXT", "A declaration shadows the package of a generated API reference.");
                }
                return p;
            }

            private boolean shadowsPackage(String name) {
                String packageName = input.getPackageDeclaration() == null ? "" : input.getPackageDeclaration().getExpression().printTrimmed();
                if (packageShadows.contains(name + ":package:" + packageName)) return true;
                J.ClassDeclaration enclosing = getCursor().firstEnclosing(J.ClassDeclaration.class);
                if (enclosing != null && inheritedShadow(enclosing.getType(), name, new HashSet<>())) return true;
                boolean[] found = {false};
                new JavaIsoVisitor<boolean[]>() {
                    @Override
                    public J.Identifier visitIdentifier(J.Identifier id, boolean[] result) {
                        if (name.equals(id.getSimpleName()) && (id.getFieldType() != null
                                || id.getType() instanceof JavaType.GenericTypeVariable)) result[0] = true;
                        return super.visitIdentifier(id, result);
                    }
                    @Override
                    public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration cd, boolean[] result) {
                        if (name.equals(cd.getSimpleName())) result[0] = true;
                        return super.visitClassDeclaration(cd, result);
                    }
                }.visit(input, found);
                return found[0];
            }

            private boolean customFind(JavaType.FullyQualified owner, Set<String> visited) {
                if (owner == null || !visited.add(owner.getFullyQualifiedName())) return false;
                if (owner.getMethods().stream().anyMatch(method -> method.getName().equals(multiple ? "findMultiple" : "find")
                        && !method.getDeclaringType().getFullyQualifiedName().startsWith("org.hibernate.")
                        && !method.getDeclaringType().getFullyQualifiedName().startsWith("jakarta.persistence."))) return true;
                if (customFind(owner.getSupertype(), visited)) return true;
                for (JavaType.FullyQualified parent : owner.getInterfaces()) if (customFind(parent, visited)) return true;
                return false;
            }

            private boolean inheritedShadow(JavaType.FullyQualified owner, String name, Set<String> visited) {
                if (owner == null || !visited.add(owner.getFullyQualifiedName())) return false;
                if (packageShadows.contains(name + ":" + owner.getFullyQualifiedName())
                        || owner.getMembers().stream().anyMatch(v -> v.getName().equals(name))) return true;
                if (inheritedShadow(owner.getSupertype(), name, visited)) return true;
                for (JavaType.FullyQualified parent : owner.getInterfaces()) {
                    if (inheritedShadow(parent, name, visited)) return true;
                }
                return inheritedShadow(owner.getOwningClass(), name, visited);
            }
        };
    }

    private static boolean sessionMethod(JavaType.Method method) {
        return method != null && TypeUtils.isAssignableTo("org.hibernate.Session", method.getDeclaringType());
    }

    private static Expression unwrap(Expression expression) {
        while (expression instanceof J.Parentheses<?> p) expression = (Expression) p.getTree();
        return expression;
    }

    private static List<Expression> arguments(J.MethodInvocation call) {
        return call.getArguments().stream().filter(e -> !(e instanceof J.Empty)).toList();
    }

    private static boolean inert(Expression original) {
        Expression e = unwrap(original);
        if (e instanceof J.Literal) return true;
        if (e instanceof J.FieldAccess field && field.getSimpleName().equals("class")) return true;
        if (e instanceof J.Identifier id) {
            if (id.getSimpleName().equals("this")) return true;
            JavaType.Variable variable = id.getFieldType();
            return variable != null && variable.getOwner() instanceof JavaType.Method
                    && !(id.getType() instanceof JavaType.FullyQualified fq && fq.getFullyQualifiedName().equals("java.lang.Boolean"));
        }
        return false;
    }

    private static boolean explicitNull(Expression expression) {
        Expression e = unwrap(expression);
        while (e instanceof J.TypeCast cast) e = unwrap(cast.getExpression());
        return e instanceof J.Literal literal && literal.getValue() == null;
    }

    private static boolean nonNullOption(Expression original) {
        Expression e = unwrap(original);
        if (e instanceof J.NewClass) return true;
        if (e instanceof J.MethodInvocation call && call.getMethodType() != null
                && TypeUtils.isOfClassType(call.getMethodType().getDeclaringType(), "jakarta.persistence.Timeout")
                && Set.of("s", "ms", "seconds", "milliseconds").contains(call.getSimpleName())) return true;
        JavaType.Variable variable = e instanceof J.FieldAccess field ? field.getName().getFieldType()
                : e instanceof J.Identifier id ? id.getFieldType() : null;
        return variable != null && variable.hasFlags(Flag.Enum);
    }

    private static List<Comment> comments(J tree) {
        List<Comment> result = new ArrayList<>();
        new JavaIsoVisitor<List<Comment>>() {
            @Override
            public Space visitSpace(Space space, Space.Location location, List<Comment> target) {
                target.addAll(space.getComments());
                return space;
            }
        }.visit(tree, result);
        return result;
    }

    private static JavaType.FullyQualified type(String name) { return JavaType.ShallowClass.build(name); }
    private static JavaType objectType() { return type("java.lang.Object"); }
    private static JavaType.Parameterized parameterized(String name, JavaType... parameters) {
        return new JavaType.Parameterized(null, type(name), List.of(parameters));
    }

    private static Expression typeExpression(String name) {
        return TypeTree.build(name.replace('$', '.')).withType(type(name));
    }

    private static J.Identifier identifier(String name, JavaType javaType) {
        return new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, List.of(), name, javaType, null);
    }

    private static Expression constant(String owner, String name) { return constant(owner, name, type(owner)); }
    private static Expression constant(String owner, String name, JavaType valueType) {
        JavaType.Variable variable = new JavaType.Variable(null, Flag.Public.getBitMask() | Flag.Static.getBitMask() | Flag.Final.getBitMask(), name, type(owner), valueType, List.of());
        return new J.FieldAccess(Tree.randomId(), Space.EMPTY, Markers.EMPTY, typeExpression(owner),
                JLeftPadded.build(identifier(name, valueType).withFieldType(variable)), valueType);
    }

    private static Expression booleanOption(Expression value, String owner, String yes, String no) {
        if (value instanceof J.Literal literal && literal.getValue() instanceof Boolean b) {
            return constant(owner, b ? yes : no).withPrefix(value.getPrefix());
        }
        Expression condition = new J.Parentheses<>(Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                JRightPadded.build(value.withPrefix(Space.EMPTY)));
        return new J.Ternary(Tree.randomId(), value.getPrefix(), Markers.EMPTY, condition,
                JLeftPadded.<Expression>build(constant(owner, yes).withPrefix(Space.SINGLE_SPACE)).withBefore(Space.SINGLE_SPACE),
                JLeftPadded.<Expression>build(constant(owner, no).withPrefix(Space.SINGLE_SPACE)).withBefore(Space.SINGLE_SPACE), type(owner));
    }

    private static Expression batch(Expression n) {
        String owner = "org.hibernate.FindMultipleOption$BatchSize";
        JavaType.Method constructor = method(owner, "<constructor>", type(owner), List.of(n), false);
        return new J.NewClass(Tree.randomId(), Space.EMPTY, Markers.EMPTY, null, Space.EMPTY,
                (TypeTree) typeExpression(owner).withPrefix(Space.SINGLE_SPACE),
                JContainer.build(List.of(JRightPadded.build(n.withPrefix(n.getPrefix().withWhitespace(""))))), null, constructor);
    }

    private static JavaType.Method method(String owner, String name, JavaType result, List<Expression> args, boolean isStatic) {
        if (owner.equals("org.hibernate.Session")) {
            // Attribute the actual varargs API signature rather than an invented overload
            // for this call's concrete argument count. Subsequent recipes can match it normally.
            return new JavaType.Method(null, Flag.Public.getBitMask() | Flag.Abstract.getBitMask() | Flag.Varargs.getBitMask(),
                    type(owner), name, result, List.of("entityType", "key", "options"),
                    List.of(args.get(0).getType(), name.equals("findMultiple") ? parameterized("java.util.List", objectType()) : objectType(),
                            new JavaType.Array(null, type("jakarta.persistence.FindOption"), null)),
                    List.of(), List.of(), null, List.of("T"));
        }
        return new JavaType.Method(null, Flag.Public.getBitMask() | (isStatic ? Flag.Static.getBitMask() : 0), type(owner), name,
                result, java.util.stream.IntStream.range(0, args.size()).mapToObj(i -> "arg" + i).toList(),
                args.stream().map(Expression::getType).toList(), List.of(), List.of(), null, List.of());
    }

    private static J.MethodInvocation invocation(Expression select, String owner, String name, List<Expression> args, JavaType result, boolean isStatic) {
        JavaType.Method method = method(owner, name, result, args, isStatic);
        List<JRightPadded<Expression>> padded = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) padded.add(JRightPadded.build(args.get(i).withPrefix(args.get(i).getPrefix().withWhitespace(i == 0 ? "" : " "))));
        if (padded.isEmpty()) padded.add(JRightPadded.build(new J.Empty(Tree.randomId(), Space.EMPTY, Markers.EMPTY)));
        return new J.MethodInvocation(Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                select == null ? null : JRightPadded.build(select.withPrefix(select.getPrefix().withWhitespace(""))), null,
                identifier(name, method), JContainer.build(padded), method);
    }

    private static J.MethodInvocation staticCall(String owner, String name, List<Expression> args, JavaType result) {
        return invocation(typeExpression(owner), owner, name, args, result, true);
    }

    /// The complete decision is built before any source edit is returned.
    private static final class Plan {
        String reason;
        String message;
        Expression key;
        List<Expression> namedPair;
        boolean varargs;
        final List<Expression> options = new ArrayList<>();
        Plan reject(String code, String explanation) {
            reason = "LOAD_ACCESS_" + code;
            message = explanation;
            return this;
        }
    }
}
