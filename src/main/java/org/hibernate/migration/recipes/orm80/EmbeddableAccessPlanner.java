package org.hibernate.migration.recipes.orm80;

import java.util.*;

import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.*;
import org.openrewrite.xml.tree.Xml;

/// Models legacy binder contexts separately from corrected declaring-class access.
/// Plans are checked across shared types and dependent paths before any source changes.
///
/// @author Steve Ebersole
final class EmbeddableAccessPlanner {
    enum Access { FIELD, PROPERTY, UNKNOWN }
    final List<J.CompilationUnit> sources = new ArrayList<>();
    final List<Xml.Document> xml = new ArrayList<>();
    final Map<UUID, Access> edits = new HashMap<>();
    final Map<String, Diagnostic> diagnostics = new TreeMap<>();
    private final Map<String, Model> models = new TreeMap<>();
    private final Set<String> xmlClasses = new HashSet<>();
    private final Map<String, Access> proposed = new TreeMap<>();
    boolean globalXml;
    private boolean globalCustom;
    private boolean planned;

    record Member(Model owner, J tree, J.CompilationUnit source, String name, JavaType type,
                  Access side, List<J.Annotation> annotations, boolean excluded) {
        boolean has(String name) { return annotation(annotations, name) != null; }
        Access override() { return access(annotations); }
        boolean id() { return has("Id") || has("EmbeddedId"); }
    }
    private static final class Model {
        final J.ClassDeclaration tree;
        final J.CompilationUnit source;
        final String name;
        final List<Member> members = new ArrayList<>();
        Model parent;
        boolean missingParent;
        Model(J.ClassDeclaration tree, J.CompilationUnit source) {
            this.tree = tree; this.source = source; this.name = tree.getType().getFullyQualifiedName();
        }
        boolean has(String name) { return annotation(tree.getAllAnnotations(), name) != null; }
        Access explicit() { return access(tree.getAllAnnotations()); }
        boolean entity() { return has("Entity"); }
        boolean embeddable() { return has("Embeddable"); }
    }
    private record Occurrence(String key, String path, Member member, String type, Access access,
                              String code, String message, List<String> ancestors) {}
    record Diagnostic(String key, Member member, String subject, String code, String message) {}
    private record Target(String name, String role, boolean plural, boolean key, String code) {}

    void plan() {
        if (planned) return;
        planned = true;
        index();
        descriptors();
        var old = occurrences(true, Map.of());
        var unmodified = occurrences(false, Map.of());
        Map<String, List<Occurrence>> uses = new TreeMap<>();
        old.values().forEach(o -> uses.computeIfAbsent(o.type, k -> new ArrayList<>()).add(o));
        Set<String> changed = new TreeSet<>();
        for (var o : old.values()) {
            var n = unmodified.get(o.key);
            if (o.code != null || n == null || n.code != null || o.access != n.access) changed.add(o.type);
        }
        // A target-only path can expose a nested mapping absent from the original member selection.
        for (var n : unmodified.values()) if (!old.containsKey(n.key)) changed.add(n.type);
        Map<String, String> rejected = new HashMap<>();
        for (String type : changed) {
            List<Occurrence> occurrences = uses.getOrDefault(type, List.of());
            Set<Access> values = new HashSet<>();
            String reason = null;
            for (var o : occurrences) {
                values.add(o.access);
                if (o.code != null) reason = o.code;
            }
            if (reason == null && values.size() > 1 && !values.contains(Access.UNKNOWN)) reason = "CONFLICT";
            Model model = models.get(type);
            if (reason == null && (values.size() != 1 || values.contains(Access.UNKNOWN) || model == null)) reason = "UNRESOLVED";
            if (reason != null) {
                rejected.put(type, reason);
                continue;
            }
            if (model.explicit() == null) proposed.put(type, values.iterator().next());
        }
        // A parent annotation may itself change a previously unchanged descendant. Include
        // those preservation requirements before checking the complete plan.
        boolean expanded;
        do {
            expanded = false;
            var target = occurrences(false, proposed);
            for (var o : old.values()) {
                var n = target.get(o.key);
                if (n == null || o.access == n.access || proposed.containsKey(o.type) || rejected.containsKey(o.type)) continue;
                Model model = models.get(o.type);
                if (model == null || model.explicit() != null) continue;
                var group = uses.get(o.type);
                if (group.stream().allMatch(use -> use.code == null && use.access == o.access && use.access != Access.UNKNOWN)) {
                    proposed.put(o.type, o.access); expanded = true;
                }
                else rejected.put(o.type, "CONFLICT");
            }
        } while (expanded);
        // Reject edits whose complete descendant subtree cannot be preserved with the plan.
        // Recompute after rejection, since removing an ancestor can invalidate dependent plans.
        boolean again;
        do {
            again = false;
            var target = occurrences(false, proposed);
            for (var o : old.values()) {
                var n = target.get(o.key);
                if (n == null || o.code != null || n.code != null || o.access != n.access) {
                    for (String type : append(o.ancestors, o.type)) {
                        if (proposed.remove(type) != null) {
                            rejected.putIfAbsent(type, "DEPENDENCY"); again = true;
                        }
                    }
                }
            }
            for (var n : target.values()) if (!old.containsKey(n.key)) {
                for (String type : append(n.ancestors, n.type)) if (proposed.remove(type) != null) {
                    rejected.putIfAbsent(type, "DEPENDENCY"); again = true;
                }
            }
        } while (again);
        // Remove redundant descendant annotations while retaining a globally preserving plan.
        for (String type : new ArrayList<>(proposed.keySet())) {
            Access value = proposed.remove(type);
            if (!preserved(old, occurrences(false, proposed), type)) proposed.put(type, value);
        }
        proposed.forEach((name, access) -> edits.put(models.get(name).tree.getId(), access));
        var finalTarget = occurrences(false, proposed);
        for (var o : old.values()) {
            var n = finalTarget.get(o.key);
            String reason = rejected.get(o.type);
            if (reason == null && (o.code != null || n == null || n.code != null || o.access != n.access)) {
                reason = o.code != null ? o.code : n != null && n.code != null ? n.code : "DEPENDENCY";
            }
            if (reason != null) report(o, reason, (reason.equals("CONFLICT") ? "Known occurrences of " + o.type + " require different legacy access values. " : "") + "Legacy " + o.access + ", target " + (n == null ? "unreachable" : n.access)
                    + ". " + (o.message == null ? "The shared type or a dependent embedding cannot be preserved safely." : o.message));
        }
        for (var n : finalTarget.values()) if (!old.containsKey(n.key)) {
            report(n, "DEPENDENCY", "Target access exposes an embedding absent from the legacy mapping.");
        }
    }

    private static boolean preserved(Map<String, Occurrence> old, Map<String, Occurrence> target, String removed) {
        for (var o : old.values()) {
            var n = target.get(o.key);
            if ((o.type.equals(removed) || o.ancestors.contains(removed))
                    && (n == null || o.code != null || n.code != null || o.access != n.access)) return false;
        }
        for (var n : target.values()) if (!old.containsKey(n.key) && (n.type.equals(removed) || n.ancestors.contains(removed))) return false;
        return true;
    }

    private void report(Occurrence o, String reason, String message) {
        diagnostics.putIfAbsent(o.key + ":" + reason, new Diagnostic(o.key, o.member, o.type + " at " + o.path, reason, message));
    }

    private void index() {
        for (var cu : sources) new JavaIsoVisitor<Void>() {
            @Override public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration cd, Void unused) {
                if (cd.getType() != null) models.put(cd.getType().getFullyQualifiedName(), new Model(cd, cu));
                return super.visitClassDeclaration(cd, unused);
            }
            @Override public J.Annotation visitAnnotation(J.Annotation a, Void unused) {
                String name = typeName(a.getType());
                if (name != null && name.startsWith("org.hibernate.annotations.") &&
                        Set.of("CompositeTypeRegistration", "CompositeTypeRegistrations", "EmbeddableInstantiatorRegistration", "EmbeddableInstantiatorRegistrations").contains(a.getSimpleName())) globalCustom = true;
                return super.visitAnnotation(a, unused);
            }
        }.visit(cu, null);
        for (Model model : models.values()) {
            JavaType.FullyQualified parent = model.tree.getType().getSupertype();
            if (parent != null && !parent.getFullyQualifiedName().equals("java.lang.Object")) {
                model.parent = models.get(parent.getFullyQualifiedName());
                model.missingParent = model.parent == null;
            }
            for (Statement statement : model.tree.getBody().getStatements()) {
                if (statement instanceof J.VariableDeclarations fields) {
                    for (var variable : fields.getVariables()) model.members.add(new Member(model, fields, model.source,
                            variable.getSimpleName(), variable.getType(), Access.FIELD, fields.getAllAnnotations(),
                            fields.hasModifier(J.Modifier.Type.Static) || fields.hasModifier(J.Modifier.Type.Transient)));
                }
                else if (statement instanceof J.MethodDeclaration method && method.getMethodType() != null
                        && method.getMethodType().getParameterTypes().isEmpty() && !method.hasModifier(J.Modifier.Type.Static)) {
                    String name = propertyName(method.getSimpleName(), method.getMethodType().getReturnType());
                    if (name != null) model.members.add(new Member(model, method, model.source, name,
                            method.getMethodType().getReturnType(), Access.PROPERTY, method.getAllAnnotations(), false));
                }
            }
        }
    }

    private static String propertyName(String method, JavaType type) {
        String stem = method.startsWith("get") && method.length() > 3 ? method.substring(3)
                : method.startsWith("is") && method.length() > 2 && type == JavaType.Primitive.Boolean ? method.substring(2) : null;
        if (stem == null || type == JavaType.Primitive.Void) return null;
        return stem.length() > 1 && Character.isUpperCase(stem.charAt(1)) ? stem : Character.toLowerCase(stem.charAt(0)) + stem.substring(1);
    }

    private Map<String, Occurrence> occurrences(boolean legacy, Map<String, Access> annotations) {
        Map<String, Occurrence> result = new TreeMap<>();
        for (Model root : models.values()) if (root.entity()) {
            Access hierarchy = hierarchy(root);
            Access binder = root.explicit() == null ? hierarchy : root.explicit();
            Access identifier = identifier(root, hierarchy);
            walk(root, root, root.name, hierarchy, binder, identifier, legacy, annotations, result,
                    List.of(), new HashSet<>(), inheritedProblem(root));
        }
        return result;
    }

    private void walk(Model root, Model container, String path, Access inherited, Access binder, Access identifier,
                      boolean legacy, Map<String, Access> annotations, Map<String, Occurrence> result,
                      List<String> ancestors, Set<String> stack, String inheritedProblem) {
        if (!stack.add(container.name)) return;
        List<Model> hierarchy = new ArrayList<>();
        for (Model c = container; c != null; c = c.parent) if (c == container || c.entity() || c.has("MappedSuperclass")) hierarchy.add(c);
        for (Model declaring : hierarchy) {
            Access classAccess = declaring.explicit() != null ? declaring.explicit()
                    : annotations.getOrDefault(declaring.name, inherited);
            Map<String, List<Member>> selected = new HashMap<>();
            for (Member member : declaring.members) {
                if (member.excluded || member.has("Transient")) continue;
                if (member.override() != null ? member.override() == Access.UNKNOWN || member.override() == member.side : classAccess == member.side || classAccess == Access.UNKNOWN) {
                    selected.computeIfAbsent(member.name, k -> new ArrayList<>()).add(member);
                }
            }
            for (var members : selected.values()) for (Member member : members) {
                String problem = inheritedProblem != null ? inheritedProblem : inheritedProblem(declaring);
                if (members.size() > 1 || classAccess == Access.UNKNOWN || member.override() == Access.UNKNOWN
                        || hierarchy.stream().anyMatch(other -> other != declaring && other.members.stream().anyMatch(m ->
                                m.name.equals(member.name) && !m.excluded && !m.has("Transient")
                                        && (m.override() != null ? m.override() == m.side : m.side == (other.explicit() != null ? other.explicit() : inherited))))) problem = "UNRESOLVED";
                for (Target target : targets(member)) {
                    Model embedded = models.get(target.name);
                    Access oldDefault = member.override() != null ? member.override() : target.plural ? identifier : binder;
                    Access next = legacy ? oldDefault : classAccess;
                    if (embedded != null && embedded.explicit() != null) next = embedded.explicit();
                    else if (!legacy) next = annotations.getOrDefault(target.name, next);
                    String code = problem != null ? problem : target.code;
                    if (code == null && (next == null || next == Access.UNKNOWN || embedded == null)) code = "UNRESOLVED";
                    if (code == null && embedded != null) code = inheritedProblem(embedded);
                    if (code == null && (globalXml || xmlDependent(target.name))) code = "XML_DEPENDENT";
                    if (code == null && embedded != null && stack.contains(embedded.name)) code = "UNRESOLVED";
                    if (code == null && (globalCustom || unsupported(member.annotations) || embedded != null && unsupported(embedded) || embedded != null && shadowsQualifier(embedded))) code = "UNSUPPORTED";
                    String childPath = path + "." + member.name + target.role;
                    String key = root.name + ":" + childPath + ":" + declaring.name + ":" + member.side
                            + ":" + (member.tree instanceof J.MethodDeclaration method ? method.getSimpleName() : member.name);
                    result.put(key, new Occurrence(key, childPath, member, target.name, next == null ? Access.UNKNOWN : next,
                            code, explanation(code, root, declaring, target, embedded, stack), ancestors));
                    if (embedded != null) {
                        // Collections initialize the old shell binder; map-key shells do not.
                        Access childBinder = target.plural ? target.key ? Access.UNKNOWN : oldDefault : binder;
                        String childProblem = code;
                        if (legacy && target.key) childProblem = "UNRESOLVED";
                        walk(root, embedded, childPath, next == null ? Access.UNKNOWN : next, childBinder, identifier,
                                legacy, annotations, result, append(ancestors, target.name), new HashSet<>(stack), childProblem);
                    }
                }
            }
        }
    }

    private String explanation(String code, Model root, Model declaring, Target target, Model embedded, Set<String> stack) {
        if (code == null) return null;
        return switch (code) {
            case "XML_DEPENDENT" -> "Supplied XML metadata or an unresolved mapping-file reference affects " + target.name + " or its containing mapping " + declaring.name + ".";
            case "UNSUPPORTED" -> "Custom mapping metadata, an unsupported class kind, embeddable inheritance, or a shadowed annotation qualifier affects " + target.name + ".";
            default -> embedded == null ? "Supply the declaration and mapping metadata for " + target.name + "."
                    : stack.contains(embedded.name) ? "Recursive embedding of " + embedded.name + " cannot be resolved."
                    : "Resolve the hierarchy, member selection, target type, and legacy binder/identifier access for " + target.name + " in entity " + root.name + ".";
        };
    }

    private String inheritedProblem(Model model) {
        for (Model c = model; c != null; c = c.parent) {
            if (globalXml || xmlDependent(c.name)) return "XML_DEPENDENT";
            if (unsupported(c)) return "UNSUPPORTED";
            if (c.missingParent) return "UNRESOLVED";
        }
        return null;
    }

    private Access hierarchy(Model root) {
        Set<Access> inferred = new HashSet<>();
        for (Model c = root; c != null; c = c.parent) {
            if (c.missingParent) return Access.UNKNOWN;
            if ((c.entity() || c.has("MappedSuperclass")) && c.explicit() == null) for (Member m : c.members) if (m.id() && m.override() == null && !m.excluded) inferred.add(m.side);
        }
        if (inferred.size() == 1) return inferred.iterator().next();
        if (inferred.size() > 1) return Access.UNKNOWN;
        // Identifier placement inside an explicitly accessed class does not establish
        // the hierarchy default for a different, unannotated declaring class.
        return Access.UNKNOWN;
    }

    private Access identifier(Model root, Access hierarchy) {
        Set<Access> values = new HashSet<>();
        for (Model c = root; c != null; c = c.parent) for (Member m : c.members) if ((c.entity() || c.has("MappedSuperclass")) && m.id()) {
            Access classAccess = c.explicit() == null ? hierarchy : c.explicit();
            values.add(m.override() == null ? classAccess : m.override());
            if (unsupported(m.annotations)) return Access.UNKNOWN;
        }
        return values.size() == 1 ? values.iterator().next() : Access.UNKNOWN;
    }

    private List<Target> targets(Member m) {
        List<Target> result = new ArrayList<>();
        if (m.has("ElementCollection") || m.has("OneToMany") || m.has("ManyToMany")) {
            boolean map = TypeUtils.isAssignableTo("java.util.Map", m.type);
            List<JavaType> args = m.type instanceof JavaType.Parameterized p ? p.getTypeParameters() : List.of();
            if (map && !m.has("MapKey")) addTarget(result, m, args.size() == 2 ? args.get(0) : null,
                    "MapKeyClass", "value", "[key]", true, true, true);
            if (m.has("ElementCollection")) addTarget(result, m, args.size() == (map ? 2 : 1) ? args.get(map ? 1 : 0) : null,
                    "ElementCollection", "targetClass", map ? "[value]" : "[element]", true, false, true);
        }
        else {
            var fq = TypeUtils.asFullyQualified(m.type);
            Model target = fq == null ? null : models.get(fq.getFullyQualifiedName());
            if (m.has("Embedded") || m.has("EmbeddedId") || target != null && target.embeddable() || fq != null && fq.getAnnotations().stream().anyMatch(a -> a.getFullyQualifiedName().equals("jakarta.persistence.Embeddable"))) {
                addTarget(result, m, m.type, "", "", "", false, false, false);
            }
        }
        return result;
    }

    private void addTarget(List<Target> result, Member member, JavaType generic, String annotation, String argument,
                           String role, boolean plural, boolean key, boolean requireEmbeddable) {
        String concrete = typeName(generic);
        Expression explicit = value(annotation(member.annotations, annotation), argument);
        String explicitName = explicit instanceof J.FieldAccess field && field.getSimpleName().equals("class") ? typeName(field.getTarget().getType()) : null;
        boolean conflict = explicitName != null && concrete != null && !concrete.equals(explicitName);
        String name = explicitName == null ? concrete : explicitName;
        if (name == null) {
            result.add(new Target("<unresolved:" + member.owner.name + "." + member.name + role + ">", role, plural, key, "UNRESOLVED"));
            return;
        }
        Model type = models.get(name);
        var fq = TypeUtils.asFullyQualified(generic);
        boolean attributedEmbeddable = fq != null && fq.getAnnotations().stream().anyMatch(a -> a.getFullyQualifiedName().equals("jakarta.persistence.Embeddable"));
        if (requireEmbeddable && (type == null || !type.embeddable()) && !attributedEmbeddable) {
            // Known basic values/entity keys do not represent embeddable candidates.
            if (explicitName == null && concrete != null) return;
            if (type != null || name.startsWith("java.")) return;
        }
        result.add(new Target(name, role, plural, key, conflict ? "UNRESOLVED" : null));
    }

    private static String typeName(JavaType type) {
        var fq = TypeUtils.asFullyQualified(type);
        return fq == null ? null : fq.getFullyQualifiedName();
    }

    private boolean shadowsQualifier(Model m) {
        return models.values().stream().anyMatch(other -> other.tree.getSimpleName().equals("jakarta")
                && other.tree.getType().getPackageName().equals(m.tree.getType().getPackageName()));
    }

    private static boolean unsupported(Model m) {
        return m.tree.getKind() != J.ClassDeclaration.Kind.Type.Class || m.embeddable() && m.parent != null
                || unsupported(m.tree.getAllAnnotations()) || m.members.stream().anyMatch(x -> x.annotations.stream()
                .filter(a -> !TypeUtils.isOfClassType(a.getType(), "jakarta.persistence.Convert"))
                .anyMatch(a -> unsupported(List.of(a))));
    }
    private static boolean unsupported(List<J.Annotation> annotations) {
        return annotations.stream().anyMatch(a -> {
            String name = typeName(a.getType());
            return name != null && (Set.of("jakarta.persistence.MapsId", "jakarta.persistence.Convert").contains(name)
                    || name.startsWith("org.hibernate.annotations.") && Set.of("AttributeAccessor", "AccessType", "CompositeType",
                    "CompositeTypeRegistration", "MapKeyCompositeType", "EmbeddableInstantiator", "EmbeddableInstantiatorRegistration", "Target").contains(a.getSimpleName()));
        });
    }

    static J.Annotation annotation(List<J.Annotation> annotations, String simple) {
        for (var a : annotations) if (TypeUtils.isOfClassType(a.getType(), "jakarta.persistence." + simple)) return a;
        return null;
    }
    private static Access access(List<J.Annotation> annotations) {
        J.Annotation annotation = annotation(annotations, "Access");
        if (annotation == null) return null;
        Expression value = value(annotation, "value");
        JavaType.Variable variable = value instanceof J.FieldAccess f ? f.getName().getFieldType()
                : value instanceof J.Identifier i ? i.getFieldType() : null;
        if (variable == null || !TypeUtils.isOfClassType(variable.getOwner(), "jakarta.persistence.AccessType")) return Access.UNKNOWN;
        return switch (variable.getName()) { case "FIELD" -> Access.FIELD; case "PROPERTY" -> Access.PROPERTY; default -> Access.UNKNOWN; };
    }
    private static Expression value(J.Annotation annotation, String name) {
        if (annotation == null || annotation.getArguments() == null) return null;
        for (Expression arg : annotation.getArguments()) {
            if (arg instanceof J.Assignment a && a.getVariable() instanceof J.Identifier id && id.getSimpleName().equals(name)) return a.getAssignment();
            if (name.equals("value") && !(arg instanceof J.Assignment) && !(arg instanceof J.Empty)) return arg;
        }
        return null;
    }
    private static List<String> append(List<String> input, String value) {
        List<String> result = new ArrayList<>(input); result.add(value); return result;
    }

    private boolean xmlDependent(String name) {
        return xmlClasses.contains(name) || xmlClasses.contains(name.replace('$', '.'));
    }
    private static String localName(Xml.Tag tag) {
        return tag.getName().substring(tag.getName().indexOf(':') + 1);
    }
    private void descriptors() {
        Set<String> paths = new HashSet<>();
        xml.forEach(d -> paths.add(d.getSourcePath().toString().replace('\\', '/')));
        for (var document : xml) {
            Xml.Tag root = document.getRoot();
            if (localName(root).equals("entity-mappings")) {
                String pkg = root.getChildren().stream().filter(t -> localName(t).equals("package")).findFirst().flatMap(Xml.Tag::getValue).orElse("").trim();
                if (root.getChildren().stream().anyMatch(t -> Set.of("persistence-unit-metadata", "access").contains(localName(t)))) globalXml = true;
                for (var tag : root.getChildren()) if (Set.of("entity", "embeddable", "mapped-superclass").contains(localName(tag))) {
                    for (var a : tag.getAttributes()) if (a.getKeyAsString().equals("class")) {
                        String name = a.getValueAsString();
                        xmlClasses.add(name.contains(".") || pkg.isEmpty() ? name : pkg + "." + name);
                    }
                }
            }
            else if (localName(root).equals("hibernate-mapping")) globalXml = true;
            else if (localName(root).equals("persistence")) for (var unit : root.getChildren()) {
                for (var child : unit.getChildren()) if (localName(child).equals("mapping-file")) {
                    String reference = child.getValue().orElse("").trim();
                    if (reference.isEmpty() || paths.stream().noneMatch(p -> p.equals(reference) || p.endsWith("/" + reference))) globalXml = true;
                }
            }
        }
    }
}
