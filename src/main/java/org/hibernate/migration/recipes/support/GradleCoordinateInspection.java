package org.hibernate.migration.recipes.support;

import org.openrewrite.*;
import org.openrewrite.groovy.tree.G;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.*;
import org.openrewrite.kotlin.tree.K;

import java.nio.file.Path;
import java.util.*;
import java.util.regex.*;

import static org.hibernate.migration.recipes.support.OrmCoordinateSupport.*;

/// Gradle script and catalog inspection shared by coordinate migration and platform adoption.
///
/// @author Steve Ebersole
public final class GradleCoordinateInspection {
    public static final Set<String> CONFIGURATIONS = Set.of("api", "implementation", "compileOnly", "compileOnlyApi", "runtimeOnly",
            "testImplementation", "testCompileOnly", "testRuntimeOnly", "annotationProcessor", "testAnnotationProcessor");

    private GradleCoordinateInspection() {}

    public record Declaration(J.MethodInvocation call, J.MethodInvocation block, String configuration, String group, String artifact,
                               String version, String role, boolean supported, String notation, List<Expression> arguments,
                               GradleOrmCatalog catalog, GradleOrmCatalog.Entry entry) {}
    public record Script(SourceFile source, List<Declaration> declarations, Map<UUID, Integer> positions, Map<String, J.Literal> safeVersions) {}

    public static void identifyCatalogs(Map<Path, GradleOrmCatalog> catalogs, Map<Path, SourceFile> scripts) {
        for (GradleOrmCatalog catalog : catalogs.values()) {
            if (catalog.source.getSourcePath().endsWith(Path.of("gradle/libs.versions.toml"))) catalog.names.add("libs");
        }
        for (SourceFile source : scripts.values()) {
            if (!source.getSourcePath().getFileName().toString().startsWith("settings")) continue;
            new JavaIsoVisitor<Integer>() {
                @Override public J.MethodInvocation visitMethodInvocation(J.MethodInvocation call, Integer p) {
                    if (call.getSimpleName().equals("from")) {
                        String filename = null;
                        for (Expression arg : call.getArguments()) if (arg instanceof J.MethodInvocation files
                                && files.getSimpleName().equals("files") && files.getArguments().size() == 1
                                && files.getArguments().get(0) instanceof J.Literal literal && literal.getValue() instanceof String s) filename = s;
                        String name = null;
                        for (Cursor c = getCursor().getParent(); c != null; c = c.getParent())
                            if (c.getValue() instanceof J.MethodInvocation create && create.getSimpleName().equals("create")
                                    && !create.getArguments().isEmpty() && create.getArguments().get(0) instanceof J.Literal literal
                                    && literal.getValue() instanceof String s) { name = s; break; }
                        if (filename != null && name != null) {
                            Path parent = source.getSourcePath().getParent();
                            Path path = (parent == null ? Path.of(filename) : parent.resolve(filename)).normalize();
                            GradleOrmCatalog catalog = catalogs.get(path);
                            if (catalog != null) catalog.names.add(name);
                        }
                    }
                    return super.visitMethodInvocation(call, p);
                }
            }.visit(source, 0);
        }
    }

    public static Script inspect(SourceFile source, Collection<GradleOrmCatalog> catalogs) {
        List<Declaration> declarations = new ArrayList<>();
        Map<String, String> literals = new HashMap<>(); Set<String> writes = new HashSet<>();
        new JavaIsoVisitor<Integer>() {
            @Override public J.VariableDeclarations.NamedVariable visitVariable(J.VariableDeclarations.NamedVariable variable, Integer p) {
                if (localDeclaration(getCursor()) && variable.getInitializer() instanceof J.Literal literal && literal.getValue() instanceof String s)
                    literals.put(variable.getSimpleName(), s);
                else writes.add(variable.getSimpleName());
                return super.visitVariable(variable, p);
            }
            @Override public J.Assignment visitAssignment(J.Assignment assignment, Integer p) {
                if (assignment.getVariable() instanceof J.Identifier id) writes.add(id.getSimpleName());
                return super.visitAssignment(assignment, p);
            }
        }.visit(source, 0);
        writes.forEach(literals::remove);
        new JavaIsoVisitor<Integer>() {
            @Override public J.MethodInvocation visitMethodInvocation(J.MethodInvocation call, Integer p) {
                List<J.MethodInvocation> parents = calls(getCursor());
                String configuration = call.getSimpleName();
                boolean dependencyContext = parents.stream().anyMatch(m -> m.getSimpleName().equals("dependencies") || m.getSimpleName().equals("constraints"));
                boolean direct = !parents.isEmpty() && Set.of("dependencies", "constraints").contains(parents.get(0).getSimpleName())
                        && parents.stream().allMatch(m -> Set.of("dependencies", "constraints", "buildscript").contains(m.getSimpleName())
                                && (m.getSelect() == null || m.getSelect() instanceof J.Identifier id && Set.of("project", "this").contains(id.getSimpleName())));
                J.MethodInvocation block = parents.stream().filter(m -> Set.of("dependencies", "constraints").contains(m.getSimpleName())).findFirst().orElse(null);
                if (dependencyContext && !Set.of("platform", "enforcedPlatform", "dependencies", "constraints").contains(configuration)) {
                    List<Expression> args = call.getArguments().stream().filter(e -> !(e instanceof J.Lambda) && !(e instanceof J.Empty)).toList();
                    String group = "", artifact = "", version = "", notation = "";
                    List<Expression> coordinateArgs = args;
                    boolean platform = false;
                    if (args.size() == 1 && args.get(0) instanceof J.MethodInvocation wrapper
                            && Set.of("platform", "enforcedPlatform").contains(wrapper.getSimpleName())) { platform = true; coordinateArgs = wrapper.getArguments(); }
                    if (coordinateArgs.size() == 1 && coordinateArgs.get(0) instanceof G.MapLiteral map) coordinateArgs = new ArrayList<>(map.getElements());
                    if (coordinateArgs.size() == 1) {
                        Expression arg = coordinateArgs.get(0);
                        String value = string(arg, literals);
                        if (!value.isEmpty()) {
                            String[] parts = value.split(":", -1);
                            if (parts.length >= 2) { group = parts[0]; artifact = parts[1]; }
                            if (parts.length >= 3) version = parts[2];
                            notation = parts.length <= 3 && !value.contains("@") ? "string" : parts.length <= 4 ? "extended" : "unsupported";
                            if (version.contains("@")) version = version.substring(0, version.indexOf('@'));
                        }
                        else if (arg instanceof G.GString || arg instanceof K.StringTemplate) {
                            Matcher matcher = Pattern.compile("^[\"']([^:]+):([^:]+):(.+)[\"']$").matcher(CoordinateSourceEdits.printed(source, arg));
                            if (matcher.matches()) {
                                group = identity(matcher.group(1), literals); artifact = identity(matcher.group(2), literals); version = matcher.group(3); notation = "string";
                                Matcher reference = Pattern.compile("\\$\\{?([A-Za-z_][A-Za-z0-9_]*)}?").matcher(version);
                                if (reference.matches() && !literals.containsKey(reference.group(1))) version = "unresolved version(...)";
                            }
                        }
                        else {
                            String accessor = arg.printTrimmed();
                            for (GradleOrmCatalog catalog : catalogs) for (String name : catalog.names) {
                                Path buildParent = source.getSourcePath().getParent();
                                Path catalogParent = catalog.source.getSourcePath().getParent();
                                Path root = catalogParent == null ? Path.of("") : catalogParent.getParent();
                                if (root != null && buildParent != null && !buildParent.startsWith(root)) continue;
                                if (accessor.startsWith(name + ".")) {
                                    String alias = accessor.substring(name.length() + 1);
                                    List<GradleOrmCatalog.Entry> entries = new ArrayList<>();
                                    if (alias.startsWith("bundles.")) {
                                        for (String member : catalog.bundles.getOrDefault(alias.substring(8), List.of())) {
                                            var entry = catalog.library(GradleOrmCatalog.accessor(member)); if (entry != null) entries.add(entry);
                                        }
                                    }
                                    else { var entry = catalog.library(alias); if (entry != null) entries.add(entry); }
                                    for (var entry : entries) if (recognizable(entry.group(), entry.artifact())) declarations.add(new Declaration(call, block,
                                            configuration, entry.group(), entry.artifact(), entry.version(), role(configuration, entry.artifact(), platform, parents),
                                            direct && CONFIGURATIONS.contains(configuration), "catalog", args, catalog, entry));
                                }
                            }
                        }
                    }
                    else {
                        Map<String, Expression> fields = fields(coordinateArgs);
                        group = string(fields.get("group"), literals); artifact = string(fields.get("name"), literals);
                        if (group.isEmpty() && fields.get("group") != null) group = "${unresolvedGroup}";
                        if (artifact.isEmpty() && fields.get("name") != null) artifact = "${unresolvedArtifact}";
                        Expression v = fields.get("version"); version = string(v, literals);
                        if (version.isEmpty() && v != null) version = v instanceof J.Identifier ? "unresolved version(...)" : CoordinateSourceEdits.printed(source, v);
                        notation = "named";
                    }
                    if (group.isEmpty() && artifact.isEmpty() && fields(coordinateArgs).isEmpty()) {
                        for (Expression argument : coordinateArgs) {
                            String[] parts = string(argument, literals).split(":", -1);
                            if (parts.length >= 2 && recognizable(parts[0], parts[1]))
                                declarations.add(new Declaration(call, block, configuration, parts[0], parts[1], parts.length >= 3 ? parts[2] : "",
                                        "library", false, "unsupported", List.of(argument), null, null));
                        }
                    }
                    if (recognizable(group, artifact) || knownArtifact(artifact) && (group.contains("$") || group.isEmpty())
                            || Set.of(GROUP, "org.hibernate").contains(group) && artifact.contains("$"))
                        declarations.add(new Declaration(call, block, configuration, group, artifact, version, notation.equals("extended") || fields(coordinateArgs).containsKey("classifier") || fields(coordinateArgs).containsKey("ext")
                                        ? "artifact" : role(configuration, artifact, platform, parents),
                                direct && (CONFIGURATIONS.contains(configuration) || configuration.equals("classpath") && target(group, artifact) != null && TOOLING.contains(target(group, artifact)) && parents.stream().anyMatch(m -> m.getSimpleName().equals("buildscript"))),
                                notation, coordinateArgs, null, null));
                }
                if (configuration.equals("id") && !call.getArguments().isEmpty() && GROUP.equals(string(call.getArguments().get(0), literals))
                        && parents.stream().anyMatch(m -> m.getSimpleName().equals("plugins"))
                        && parents.stream().noneMatch(m -> m.getSimpleName().equals("version")))
                    declarations.add(new Declaration(call, block, "plugin", "plugin", GROUP, "", "plugin", true, "pluginMissing", call.getArguments(), null, null));
                if (configuration.equals("version") && !call.getArguments().isEmpty()) {
                    String plugin = call.getSelect() instanceof J.MethodInvocation id && id.getSimpleName().equals("id")
                            && !id.getArguments().isEmpty() ? string(id.getArguments().get(0), literals) : "";
                    if (plugin.equals(GROUP)) declarations.add(new Declaration(call, block, "plugin", "plugin", GROUP,
                            (string(call.getArguments().get(0), literals).isEmpty() ? "unresolved version(...)" : string(call.getArguments().get(0), literals)), "plugin", parents.stream().allMatch(m -> Set.of("apply", "plugins").contains(m.getSimpleName())),
                            "plugin", call.getArguments(), null, null));
                }
                return super.visitMethodInvocation(call, p);
            }
        }.visit(source, 0);
        Set<UUID> ids = new HashSet<>();
        declarations.forEach(d -> { ids.add(d.call.getId()); if (d.block != null) ids.add(d.block.getId()); });
        Map<String, J.Literal> definitions = new HashMap<>(); Map<String, Integer> reads = new HashMap<>();
        new JavaIsoVisitor<Integer>() {
            @Override public J.VariableDeclarations.NamedVariable visitVariable(J.VariableDeclarations.NamedVariable variable, Integer p) {
                if (variable.getInitializer() instanceof J.Literal literal && literal.getValue() instanceof String
                        && literals.containsKey(variable.getSimpleName())) definitions.put(variable.getSimpleName(), literal);
                return super.visitVariable(variable, p);
            }
            @Override public J.Identifier visitIdentifier(J.Identifier identifier, Integer p) {
                if (!(getCursor().getParentTreeCursor().getValue() instanceof J.VariableDeclarations.NamedVariable variable
                        && variable.getName().getId().equals(identifier.getId()))) reads.merge(identifier.getSimpleName(), 1, Integer::sum);
                return super.visitIdentifier(identifier, p);
            }
        }.visit(source, 0);
        definitions.entrySet().removeIf(e -> {
            List<Declaration> uses = declarations.stream().filter(d -> versionReference(source, d).equals(e.getKey())).toList();
            return uses.isEmpty() || reads.getOrDefault(e.getKey(), 0) != uses.size()
                    || uses.stream().anyMatch(d -> !d.supported || !validVersion(d.version) || (!d.role.equals("plugin") && target(d.group, d.artifact) == null)
                            || declarations.stream().anyMatch(other -> other != d && Objects.equals(other.block, d.block)
                                    && other.configuration.equals(d.configuration) && other.role.equals(d.role)
                                    && Objects.equals(target(other.group, other.artifact), target(d.group, d.artifact))));
        });
        definitions.values().forEach(literal -> ids.add(literal.getId()));
        return new Script(source, declarations, CoordinateSourceEdits.offsets(source, ids), definitions);
    }

    public static boolean localDeclaration(Cursor cursor) {
        for (Cursor c = cursor.getParent(); c != null; c = c.getParent()) {
            Object tree = c.getValue();
            if (tree instanceof J.Lambda || tree instanceof J.ClassDeclaration || tree instanceof J.MethodDeclaration || tree instanceof J.If
                    || tree instanceof J.ForLoop || tree instanceof J.ForEachLoop || tree instanceof J.WhileLoop || tree instanceof J.DoWhileLoop
                    || tree instanceof J.Try) return false;
        }
        return true;
    }
    public static List<J.MethodInvocation> calls(Cursor cursor) {
        List<J.MethodInvocation> result = new ArrayList<>();
        for (Cursor c = cursor.getParent(); c != null; c = c.getParent()) if (c.getValue() instanceof J.MethodInvocation method) result.add(method);
        return result;
    }
    public static String role(String configuration, String artifact, boolean platform, List<J.MethodInvocation> parents) {
        return platform || PLATFORM.equals(artifact) ? "platform" : configuration.contains("annotationProcessor") || configuration.contains("AnnotationProcessor")
                || configuration.equals("classpath") || TOOLING.contains(artifact) || artifact.equals("hibernate-jpamodelgen") ? "tooling"
                : parents.stream().anyMatch(m -> m.getSimpleName().equals("constraints")) ? "constraint" : "library";
    }
    public static Map<String, Expression> fields(List<Expression> args) {
        Map<String, Expression> fields = new LinkedHashMap<>();
        for (Expression arg : args) {
            if (arg instanceof G.MapEntry entry && entry.getKey() instanceof J.Literal key && key.getValue() instanceof String s) fields.put(s, entry.getValue());
            else if (arg instanceof J.Assignment assignment && assignment.getVariable() instanceof J.Identifier id) fields.put(id.getSimpleName(), assignment.getAssignment());
        }
        return fields;
    }
    public static String identity(String value, Map<String, String> literals) {
        Matcher reference = Pattern.compile("\\$\\{?([A-Za-z_][A-Za-z0-9_]*)}?").matcher(value);
        return reference.matches() ? literals.getOrDefault(reference.group(1), value) : value;
    }
    public static String string(Expression expression, Map<String, String> literals) {
        if (expression instanceof J.Literal literal && literal.getValue() instanceof String s) return s;
        if (expression instanceof J.Identifier id) return literals.getOrDefault(id.getSimpleName(), "");
        return "";
    }
    public static boolean validVersion(String version) {
        return version.isEmpty() || simpleVersion(version) || version.matches("\\$[A-Za-z_][A-Za-z0-9_]*")
                || version.matches("\\$\\{[A-Za-z_][A-Za-z0-9_]*}");
    }
    public static String retainedComments(SourceFile source, J argument) {
        StringBuilder retained = new StringBuilder();
        Set<Comment> comments = Collections.newSetFromMap(new IdentityHashMap<>());
        new JavaIsoVisitor<Integer>() {
            @Override public Space visitSpace(Space space, Space.Location location, Integer p) {
                if (space != argument.getPrefix()) for (Comment comment : space.getComments()) if (comments.add(comment))
                    retained.append(' ').append(comment.printComment(new Cursor(null, source))).append(comment.getSuffix());
                return space;
            }
        }.visit(argument, 0);
        return retained.toString();
    }
    public static String versionReference(SourceFile source, Declaration d) {
        if (d.notation.equals("string") && !d.arguments.isEmpty()) {
            Matcher matcher = Pattern.compile(".*:\\$\\{?([A-Za-z_][A-Za-z0-9_]*)}?['\"]$").matcher(CoordinateSourceEdits.printed(source, d.arguments.get(0)));
            return matcher.matches() ? matcher.group(1) : "";
        }
        Expression expression = d.notation.equals("plugin") && !d.arguments.isEmpty() ? d.arguments.get(0) : fields(d.arguments).get("version");
        return expression instanceof J.Identifier id ? id.getSimpleName() : "";
    }
}
