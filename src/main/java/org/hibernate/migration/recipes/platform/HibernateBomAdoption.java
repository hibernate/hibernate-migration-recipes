package org.hibernate.migration.recipes.platform;

import org.hibernate.migration.recipes.support.CoordinateSourceEdits;
import org.hibernate.migration.recipes.support.CoordinateXmlEdits;
import org.hibernate.migration.recipes.support.EnhancementMigrationSupport;
import org.hibernate.migration.recipes.support.GradleCoordinateInspection;
import org.hibernate.migration.recipes.support.GradleCoordinateInspection.Declaration;
import org.hibernate.migration.recipes.support.GradleCoordinateInspection.Script;
import org.hibernate.migration.recipes.support.GradleOrmCatalog;
import org.hibernate.migration.recipes.support.OrmCoordinateSupport;
import org.hibernate.migration.recipes.table.SkippedMigrations;
import org.hibernate.migration.recipes.xml.DescriptorVisitor;
import org.jspecify.annotations.NonNull;
import org.openrewrite.*;
import org.openrewrite.groovy.tree.G;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.*;
import org.openrewrite.kotlin.tree.K;
import org.openrewrite.maven.tree.MavenRepository;
import org.openrewrite.toml.tree.Toml;
import org.openrewrite.xml.XmlIsoVisitor;
import org.openrewrite.xml.tree.Xml;
import org.openrewrite.xml.tree.Content;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;

import static org.hibernate.migration.recipes.support.GradleCoordinateInspection.*;
import static org.hibernate.migration.recipes.support.OrmCoordinateSupport.*;

/// Shared build-file adoption engine; each public recipe selects its BOM and management policy.
///
/// @author Steve Ebersole
abstract class HibernateBomAdoption extends ScanningRecipe<HibernateBomAdoption.Plan> {
    private final transient SkippedMigrations skipped = new SkippedMigrations(this);

    public abstract String getTargetVersion();
    protected abstract String bomArtifact();
    private boolean ormOnly() { return BOM.equals(bomArtifact()); }

    protected static String defaultTargetVersion() {
        Properties props = new Properties();
        try (InputStream in = HibernateBomAdoption.class.getResourceAsStream("/META-INF/rewrite/platform-version.properties")) {
            if (in != null) props.load(in);
        }
        catch (IOException ignored) {}
        return props.getProperty("targetVersion", "8.0.0.Beta3");
    }
    @Override public @NonNull Validated<Object> validate() { return super.validate().and(OrmCoordinateSupport.validate(getTargetVersion())); }
    @Override public boolean causesAnotherCycle() { return true; }

    static final class Plan {
        final Map<Path, SourceFile> scripts = new LinkedHashMap<>();
        final Map<Path, GradleOrmCatalog> catalogs = new LinkedHashMap<>();
        final Map<Path, List<CoordinateSourceEdits.Edit>> edits = new HashMap<>();
        final Set<GradleOrmCatalog.Entry> protectedEntries = new HashSet<>();
        boolean ready;
    }

    @Override public Plan getInitialValue(ExecutionContext ctx) { return new Plan(); }

    @Override public TreeVisitor<?, ExecutionContext> getScanner(Plan plan) {
        plan.ready = false;
        plan.edits.clear();
        plan.protectedEntries.clear();
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override public Tree preVisit(Tree tree, ExecutionContext ctx) {
                stopAfterPreVisit();
                if (!(tree instanceof SourceFile source) || !validate().isValid()) return tree;
                String filename = source.getSourcePath().getFileName().toString();
                if (source instanceof Toml.Document toml) plan.catalogs.put(source.getSourcePath(), new GradleOrmCatalog(toml));
                else if ((source instanceof G.CompilationUnit || source instanceof K.CompilationUnit)
                        && Set.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts").contains(filename))
                    plan.scripts.put(source.getSourcePath(), source);
                return tree;
            }
        };
    }

    @Override public TreeVisitor<?, ExecutionContext> getVisitor(Plan plan) {
        boolean[] stale = {false};
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override public Tree preVisit(Tree tree, ExecutionContext ctx) {
                stopAfterPreVisit();
                if (!(tree instanceof SourceFile source) || !validate().isValid()) return tree;
                if (source instanceof Xml.Document doc) return ormOnly() ? adoptMavenBom(doc, ctx) : adoptMaven(doc, ctx);
                if (!stale[0]) {
                    SourceFile scanned = plan.scripts.get(source.getSourcePath());
                    if (scanned != null && scanned != source) stale[0] = true;
                    else if (source instanceof Toml.Document) {
                        GradleOrmCatalog catalog = plan.catalogs.get(source.getSourcePath());
                        if (catalog != null && catalog.source != source) stale[0] = true;
                    }
                }
                if (stale[0]) return source;
                if (!plan.ready) { prepareGradle(plan, ctx); plan.ready = true; }
                return CoordinateSourceEdits.apply(source, plan.edits.getOrDefault(source.getSourcePath(), List.of()), ctx);
            }
        };
    }

    // ── Maven ──

    private record MavenCandidate(Xml.Tag tag, Xml.Tag scope, String role, String group, String artifact, String version) {}

    private Xml.Document adoptMaven(Xml.Document doc, ExecutionContext ctx) {
        if (!validate().isValid()) return doc;
        String ns = DescriptorVisitor.namespace(doc.getRoot(), DescriptorVisitor.namespaces(doc.getRoot(), Map.of("", "")));
        if (!"project".equals(local(doc.getRoot())) || !(ns.isEmpty() || "http://maven.apache.org/POM/4.0.0".equals(ns))) return doc;

        Set<UUID> processed = ctx.computeMessageIfAbsent(getName() + ".processed:" + getTargetVersion(), k -> new HashSet<>());
        if (!processed.add(doc.getId())) return doc;

        Map<String, String> properties = new HashMap<>();
        children(doc.getRoot(), "properties").forEach(t -> t.getChildren().forEach(p -> properties.put(local(p), p.getValue().orElse(""))));

        List<MavenCandidate> candidates = new ArrayList<>();
        collectMaven(doc.getRoot(), "", doc.getRoot(), ns, Map.of("", ""), properties, candidates);
        if (candidates.isEmpty()) return doc;

        List<MavenCandidate> platforms = candidates.stream().filter(c -> PLATFORM.equals(c.artifact)).toList();
        if (!platforms.isEmpty()) return doc;

        boolean hasParent = !children(doc.getRoot(), "parent").isEmpty();
        if (hasParent) return doc;

        List<MavenRepository> repositories = new ArrayList<>();
        children(doc.getRoot(), "repositories").forEach(t -> children(t, "repository").forEach(r -> {
            String url = value(r, "url"); if (!url.isBlank() && !url.contains("${"))
                repositories.add(MavenRepository.builder().id(value(r, "id")).uri(url).releases("true").snapshots("true").build());
        }));

        boolean hasLibraries = candidates.stream().anyMatch(c -> c.role.equals("library"));
        if (!hasLibraries) return doc;

        Set<String> covered = managed(bomArtifact(), getTargetVersion(), repositories, ctx);
        if (covered.isEmpty()) return doc;

        Map<UUID, Xml.Tag> replacements = new HashMap<>();
        Map<UUID, List<MavenCandidate>> scopes = new LinkedHashMap<>();
        for (MavenCandidate c : candidates) scopes.computeIfAbsent(c.scope.getId(), k -> new ArrayList<>()).add(c);

        Set<UUID> ids = new HashSet<>(); candidates.forEach(c -> ids.add(c.tag.getId()));
        Map<UUID, Integer> positions = EnhancementMigrationSupport.offsets(doc, ids);

        for (List<MavenCandidate> scope : scopes.values()) {
            boolean libraries = false;
            List<MavenCandidate> scopePlatforms = scope.stream().filter(c -> PLATFORM.equals(c.artifact)).toList();
            for (MavenCandidate c : scope) {
                if (c.role.equals("library") && !TOOLING.contains(c.artifact)) {
                    boolean omit = covered.contains(c.artifact)
                            && value(c.tag, "classifier").isEmpty()
                            && (value(c.tag, "type").isEmpty() || value(c.tag, "type").equals("jar"));
                    if (omit) {
                        replacements.put(c.tag.getId(), CoordinateXmlEdits.remove(c.tag, "version"));
                    }
                    else {
                        report(this, skipped, doc, positions.get(c.tag.getId()), GROUP + ":" + c.artifact, MANAGEMENT,
                                "Coordinates aligned; explicit version retained because effective platform management could not be verified.", ctx);
                    }
                    libraries = true;
                }
            }
            if (libraries && scopePlatforms.isEmpty()) {
                Xml.Tag scopeTag = scope.get(0).scope;
                Xml.Tag bom = Xml.Tag.build("<" + name(scopeTag, "dependency") + "><" + name(scopeTag, "groupId") + ">" + GROUP
                        + "</" + name(scopeTag, "groupId") + "><" + name(scopeTag, "artifactId") + ">" + PLATFORM
                        + "</" + name(scopeTag, "artifactId") + "><" + name(scopeTag, "version") + ">" + getTargetVersion()
                        + "</" + name(scopeTag, "version") + "><" + name(scopeTag, "type") + ">pom</" + name(scopeTag, "type")
                        + "><" + name(scopeTag, "scope") + ">import</" + name(scopeTag, "scope") + "></" + name(scopeTag, "dependency") + ">");
                List<Xml.Tag> management = children(scopeTag, "dependencyManagement");
                Xml.Tag dm = management.isEmpty() ? Xml.Tag.build("<" + name(scopeTag, "dependencyManagement") + "><" + name(scopeTag, "dependencies")
                        + "></" + name(scopeTag, "dependencies") + "></" + name(scopeTag, "dependencyManagement") + ">") : management.get(0);
                List<Xml.Tag> dependencies = children(dm, "dependencies");
                Xml.Tag deps = dependencies.isEmpty() ? Xml.Tag.build("<" + name(dm, "dependencies") + "></" + name(dm, "dependencies") + ">") : dependencies.get(0);
                Xml.Tag withBom = CoordinateXmlEdits.append(deps, bom);
                if (!dependencies.isEmpty()) replacements.put(deps.getId(), withBom);
                else dm = CoordinateXmlEdits.append(dm, withBom);
                if (!management.isEmpty()) { if (dependencies.isEmpty()) replacements.put(dm.getId(), dm); }
                else {
                    final Xml.Tag oldDeps = deps;
                    dm = dm.withContent(dm.getContent().stream().map(t -> t.getId().equals(oldDeps.getId()) ? withBom : t).toList());
                    replacements.put(scopeTag.getId(), CoordinateXmlEdits.append(scopeTag, dm));
                }
            }
        }

        if (replacements.isEmpty()) return doc;
        Xml.Document result = (Xml.Document) new XmlIsoVisitor<ExecutionContext>() {
            @Override public Xml.@NonNull Tag visitTag(Xml.@NonNull Tag tag, @NonNull ExecutionContext p) {
                return super.visitTag(replacements.getOrDefault(tag.getId(), tag), p);
            }
        }.visitNonNull(doc, ctx);
        return result.printAll().equals(doc.printAll()) ? doc : result;
    }

    private Xml.Document adoptMavenBom(Xml.Document doc, ExecutionContext ctx) {
        String ns = DescriptorVisitor.namespace(doc.getRoot(), DescriptorVisitor.namespaces(doc.getRoot(), Map.of("", "")));
        if (!"project".equals(local(doc.getRoot())) || !("".equals(ns) || "http://maven.apache.org/POM/4.0.0".equals(ns))) return doc;
        Xml.Tag root = doc.getRoot();
        Map<String, String> properties = new HashMap<>();
        children(root, "properties").forEach(t -> t.getChildren().forEach(p -> properties.put(local(p), p.getValue().orElse(""))));
        List<MavenCandidate> candidates = new ArrayList<>();
        collectMaven(root, "", root, ns, Map.of("", ""), properties, candidates, true);
        Set<String> profileProperties = new HashSet<>();
        children(root, "profiles").forEach(profiles -> children(profiles, "profile").forEach(profile ->
                children(profile, "properties").forEach(props -> props.getChildren().forEach(prop -> profileProperties.add(local(prop))))));
        if (candidates.stream().noneMatch(c -> c.role.equals("library"))) return doc;
        Set<UUID> ids = new HashSet<>(); candidates.forEach(c -> ids.add(c.tag.getId()));
        Map<UUID, Integer> positions = EnhancementMigrationSupport.offsets(doc, ids);
        boolean rootPlatform = candidates.stream().anyMatch(c -> c.scope == root && GROUP.equals(c.group) && c.artifact.equals(PLATFORM));
        if (rootPlatform) return doc;
        if (!children(root, "parent").isEmpty()) {
            mavenReport(doc, candidates, positions, "Inherited parent management cannot be verified for " + BOM + ".", ctx);
            return doc;
        }
        List<MavenRepository> repositories = new ArrayList<>();
        children(root, "repositories").forEach(t -> children(t, "repository").forEach(r -> {
            String url = value(r, "url");
            if (!url.isBlank() && !url.contains("${")) repositories.add(MavenRepository.builder()
                    .id(value(r, "id")).uri(url).releases("true").snapshots("true").build());
        }));
        Set<String> covered = managed(BOM, getTargetVersion(), repositories, ctx);
        if (covered.isEmpty()) {
            mavenReport(doc, candidates, positions, "Published " + BOM + " management could not be resolved.", ctx);
            return doc;
        }
        Map<UUID, List<MavenCandidate>> scopes = new LinkedHashMap<>();
        candidates.forEach(c -> scopes.computeIfAbsent(c.scope.getId(), k -> new ArrayList<>()).add(c));
        Map<UUID, Xml.Tag> replacements = new HashMap<>();
        List<List<MavenCandidate>> orderedScopes = new ArrayList<>(scopes.values());
        orderedScopes.sort(Comparator.comparing(scope -> scope.get(0).scope != root));
        boolean rootImported = false;
        for (var scope : orderedScopes) {
            Xml.Tag owner = scope.get(0).scope;
            if (scope.stream().anyMatch(c -> GROUP.equals(c.group) && c.artifact.equals(PLATFORM))) continue;
            if (candidates.stream().anyMatch(c -> (!GROUP.equals(c.group) || !CANONICAL.contains(c.artifact)) && (c.scope == root || c.scope == owner))) {
                mavenReport(doc, scope, positions, "An ORM management identity is unresolved; " + BOM + " applicability cannot be verified.", ctx);
                continue;
            }
            List<MavenCandidate> boms = candidates.stream().filter(c -> c.artifact.equals(BOM)
                    && (c.scope == owner || c.scope == root)).toList();
            boolean conflict = boms.size() > 1 || boms.stream().anyMatch(c -> !c.role.equals("management")
                    || shadowed(c, root, profileProperties) || !getTargetVersion().equals(c.version) || !"pom".equals(value(c.tag, "type"))
                    || !"import".equals(value(c.tag, "scope")) || !value(c.tag, "classifier").isEmpty());
            if (conflict || children(owner, "dependencyManagement").size() > 1
                    || children(owner, "dependencyManagement").stream().anyMatch(dm -> children(dm, "dependencies").size() > 1)) {
                mavenReport(doc, scope, positions, "Conflicting, duplicate, or unresolved " + BOM + " management.", ctx);
                continue;
            }
            // An existing import must precede other imports for overlapping ORM entries.
            boolean importOrder = boms.isEmpty() || firstImport(boms.get(0).scope, boms.get(0).tag);
            boolean adopt = false;
            for (MavenCandidate c : scope) {
                if (!c.role.equals("library") || TOOLING.contains(c.artifact) || c.artifact.equals(BOM)) continue;
                // Root dependencies can also be affected by explicitly managed profile overrides.
                List<MavenCandidate> overrides = candidates.stream().filter(m -> m.role.equals("management")
                        && m.artifact.equals(c.artifact) && !"import".equals(value(m.tag, "scope"))
                        && (m.scope == owner || m.scope == root || owner == root)).toList();
                boolean directSafe = overrides.stream().allMatch(m -> !shadowed(m, root, profileProperties) && getTargetVersion().equals(m.version)
                        && children(m.tag, "version").size() == 1 && value(m.tag, "classifier").isEmpty()
                        && (value(m.tag, "type").isEmpty() || "jar".equals(value(m.tag, "type"))));
                boolean supported = !shadowed(c, root, profileProperties) && covered.contains(c.artifact) && value(c.tag, "classifier").isEmpty()
                        && (value(c.tag, "type").isEmpty() || "jar".equals(value(c.tag, "type")))
                        && children(c.tag, "version").size() <= 1 && (c.version.isEmpty() || simpleVersion(c.version));
                if (supported && directSafe && importOrder) {
                    replacements.put(c.tag.getId(), CoordinateXmlEdits.remove(c.tag, "version"));
                    adopt = true;
                }
                else mavenReport(doc, List.of(c), positions,
                        "Explicit version retained: " + BOM + " coverage, import precedence, or direct management could not be verified.", ctx);
            }
            if (adopt && boms.isEmpty() && !rootImported) {
                addMavenImport(owner, replacements, true);
                if (owner == root) rootImported = true;
            }
        }
        if (replacements.isEmpty()) return doc;
        return (Xml.Document) new XmlIsoVisitor<ExecutionContext>() {
            @Override public Xml.@NonNull Tag visitTag(Xml.@NonNull Tag tag, @NonNull ExecutionContext p) {
                return super.visitTag(replacements.getOrDefault(tag.getId(), tag), p);
            }
        }.visitNonNull(doc, ctx);
    }

    private void mavenReport(Xml.Document doc, List<MavenCandidate> scope, Map<UUID, Integer> positions,
            String message, ExecutionContext ctx) {
        for (var c : scope) if (c.role.equals("library") && !TOOLING.contains(c.artifact)
                && !Set.of(BOM, PLATFORM).contains(c.artifact))
            report(this, skipped, doc, positions.get(c.tag.getId()), GROUP + ":" + c.artifact, MANAGEMENT, message, ctx);
    }

    private static boolean firstImport(Xml.Tag scope, Xml.Tag bom) {
        for (Xml.Tag dm : children(scope, "dependencyManagement")) for (Xml.Tag deps : children(dm, "dependencies"))
            for (Xml.Tag dependency : children(deps, "dependency")) {
                if (dependency.getId().equals(bom.getId())) return true;
                if ("pom".equals(value(dependency, "type")) && "import".equals(value(dependency, "scope"))) return false;
            }
        return false;
    }

    private void addMavenImport(Xml.Tag scope, Map<UUID, Xml.Tag> replacements, boolean first) {
        Xml.Tag bom = Xml.Tag.build("<" + name(scope, "dependency") + "><" + name(scope, "groupId") + ">" + GROUP
                + "</" + name(scope, "groupId") + "><" + name(scope, "artifactId") + ">" + bomArtifact()
                + "</" + name(scope, "artifactId") + "><" + name(scope, "version") + ">" + getTargetVersion()
                + "</" + name(scope, "version") + "><" + name(scope, "type") + ">pom</" + name(scope, "type")
                + "><" + name(scope, "scope") + ">import</" + name(scope, "scope") + "></" + name(scope, "dependency") + ">");
        List<Xml.Tag> management = children(scope, "dependencyManagement");
        Xml.Tag dm = management.isEmpty() ? Xml.Tag.build("<" + name(scope, "dependencyManagement") + "/>") : management.get(0);
        List<Xml.Tag> dependencies = children(dm, "dependencies");
        Xml.Tag deps = dependencies.isEmpty() ? Xml.Tag.build("<" + name(scope, "dependencies") + "/>") : dependencies.get(0);
        Xml.Tag updated = CoordinateXmlEdits.append(deps, bom);
        if (first && !deps.getChildren().isEmpty()) {
            List<Content> content = new ArrayList<>(updated.getContent());
            var added = content.remove(content.size() - 1);
            content.add(0, added);
            updated = updated.withContent(content);
        }
        if (!dependencies.isEmpty()) replacements.put(deps.getId(), updated);
        else {
            Xml.Tag withDependencies = CoordinateXmlEdits.append(dm, updated);
            if (!management.isEmpty()) replacements.put(dm.getId(), withDependencies);
            else replacements.put(scope.getId(), CoordinateXmlEdits.append(scope, withDependencies));
        }
    }

    private static boolean shadowed(MavenCandidate candidate, Xml.Tag root, Set<String> profileProperties) {
        if (candidate.scope != root) return false;
        String version = value(candidate.tag, "version");
        return profileProperties.stream().anyMatch(property -> version.contains("${" + property + "}"));
    }

    private static void collectMaven(Xml.Tag tag, String path, Xml.Tag scope, String namespace, Map<String, String> inherited,
            Map<String, String> props, List<MavenCandidate> candidates) {
        collectMaven(tag, path, scope, namespace, inherited, props, candidates, false);
    }

    private static void collectMaven(Xml.Tag tag, String path, Xml.Tag scope, String namespace, Map<String, String> inherited,
            Map<String, String> props, List<MavenCandidate> candidates, boolean scopedProperties) {
        Map<String, String> ns = DescriptorVisitor.namespaces(tag, inherited);
        if (!namespace.equals(DescriptorVisitor.namespace(tag, ns))) return;
        String here = path.isEmpty() ? local(tag) : path + "/" + local(tag);
        if (here.equals("project/profiles/profile")) {
            scope = tag;
            if (scopedProperties) {
                props = new HashMap<>(props);
                for (Xml.Tag properties : children(tag, "properties")) for (Xml.Tag property : properties.getChildren())
                    props.put(local(property), property.getValue().orElse(""));
            }
        }
        String relative = here.startsWith("project/profiles/profile/") ? "project/" + here.substring("project/profiles/profile/".length()) : here;
        String role = switch (relative) {
            case "project/dependencies/dependency" -> "library";
            case "project/dependencyManagement/dependencies/dependency" -> "management";
            case "project/build/plugins/plugin", "project/build/pluginManagement/plugins/plugin" -> "plugin";
            default -> "";
        };
        if (relative.matches("project/build/(?:pluginManagement/)?plugins/plugin/(?:executions/execution/)?configuration/annotationProcessorPaths/path"))
            role = "processor";
        if (!role.isEmpty()) {
            String group = resolve(childValue(tag, "groupId", namespace, ns), props);
            String artifact = resolve(childValue(tag, "artifactId", namespace, ns), props);
            if (GROUP.equals(group) && CANONICAL.contains(artifact)
                    || scopedProperties && (CANONICAL.contains(artifact) && (group.isEmpty() || group.contains("${"))
                            || GROUP.equals(group) && (artifact.isEmpty() || artifact.contains("${"))))
                candidates.add(new MavenCandidate(tag, scope, role, group, artifact, resolve(childValue(tag, "version", namespace, ns), props)));
        }
        for (Xml.Tag child : tag.getChildren()) collectMaven(child, here, scope, namespace, ns, props, candidates, scopedProperties);
    }

    private static String childValue(Xml.Tag tag, String name, String namespace, Map<String, String> ns) {
        List<Xml.Tag> values = tag.getChildren().stream().filter(t -> local(t).equals(name)
                && namespace.equals(DescriptorVisitor.namespace(t, DescriptorVisitor.namespaces(t, ns)))).toList();
        return values.size() == 1 ? values.get(0).getValue().orElse("").trim() : "";
    }

    private static String resolve(String value, Map<String, String> props) {
        return value.matches("\\$\\{[A-Za-z0-9_.-]+}") ? props.getOrDefault(value.substring(2, value.length() - 1), value) : value;
    }

    // ── Gradle ──

    private void prepareGradle(Plan plan, ExecutionContext ctx) {
        if (!validate().isValid()) return;
        GradleCoordinateInspection.identifyCatalogs(plan.catalogs, plan.scripts);

        List<Script> scripts = new ArrayList<>();
        for (SourceFile source : plan.scripts.values()) {
            if (source.getSourcePath().getFileName().toString().startsWith("settings")) continue;
            scripts.add(GradleCoordinateInspection.inspect(source, plan.catalogs.values()));
        }

        boolean libraries = scripts.stream().flatMap(s -> s.declarations().stream()).anyMatch(d -> d.role().equals("library"))
                || plan.catalogs.values().stream().filter(c -> !c.names.isEmpty()).flatMap(c -> c.entries.stream())
                        .anyMatch(e -> e.role().equals("library") && target(e.group(), e.artifact()) != null);
        List<MavenRepository> repositories = repositories(plan);
        Set<String> covered = libraries ? managed(bomArtifact(), getTargetVersion(), repositories, ctx) : Set.of();
        if (covered.isEmpty()) {
            if (ormOnly()) for (Script script : scripts) for (Declaration d : script.declarations())
                if (d.role().equals("library") && !protectedConfiguration(script, d.configuration()))
                    report(this, skipped, script.source(), script.positions().get(d.call().getId()),
                            GROUP + ":" + d.artifact(), MANAGEMENT, "Published " + BOM + " management could not be resolved.", ctx);
            return;
        }

        Map<GradleOrmCatalog.Entry, List<Declaration>> consumers = new HashMap<>();
        Set<Declaration> managedConsumers = new HashSet<>();
        Set<GradleOrmCatalog.Entry> omitted = new HashSet<>();

        for (Script script : scripts) for (Declaration d : script.declarations())
            if (d.entry() != null) consumers.computeIfAbsent(d.entry(), k -> new ArrayList<>()).add(d);

        for (Script script : scripts)
            migrateGradleScript(script, covered, consumers, managedConsumers, plan, ctx);

        for (GradleOrmCatalog catalog : plan.catalogs.values()) {
            for (var entry : catalog.entries) {
                List<Declaration> uses = consumers.getOrDefault(entry, List.of());
                if (!uses.isEmpty() && uses.stream().allMatch(managedConsumers::contains)
                        && completeConsumers(catalog, entry, scripts, plan)) omitted.add(entry);
            }
            if (catalog.names.isEmpty()) continue;
            migrateGradleCatalog(catalog, omitted, plan, ctx);
        }
    }

    private static List<MavenRepository> repositories(Plan plan) {
        List<MavenRepository> repositories = new ArrayList<>();
        for (SourceFile source : plan.scripts.values()) new JavaIsoVisitor<Integer>() {
            @Override public J.MethodInvocation visitMethodInvocation(J.MethodInvocation call, Integer p) {
                if (call.getSimpleName().equals("url") || call.getSimpleName().equals("maven"))
                    for (Expression arg : call.getArguments()) if (arg instanceof J.Literal literal && literal.getValue() instanceof String s
                            && (s.startsWith("https://") || s.startsWith("file:")))
                        repositories.add(MavenRepository.builder().id("gradle-" + repositories.size()).uri(s).releases("true").snapshots("true").build());
                return super.visitMethodInvocation(call, p);
            }
        }.visit(source, 0);
        return repositories;
    }

    // Include published variants: an implementation BOM cannot manage an exported API dependency.
    private static Set<String> resolutionScopes(String configuration) {
        return switch (configuration) {
            case "api" -> Set.of("compile", "runtime", "testCompile", "testRuntime", "apiElements", "runtimeElements");
            case "implementation" -> Set.of("compile", "runtime", "testCompile", "testRuntime", "runtimeElements");
            case "compileOnlyApi" -> Set.of("compile", "testCompile", "apiElements");
            case "compileOnly" -> Set.of("compile");
            case "runtimeOnly" -> Set.of("runtime", "testRuntime", "runtimeElements");
            case "testImplementation" -> Set.of("testCompile", "testRuntime");
            case "testCompileOnly" -> Set.of("testCompile");
            case "testRuntimeOnly" -> Set.of("testRuntime");
            default -> Set.of(configuration);
        };
    }

    private static boolean configurationInherits(String child, String parent) {
        return resolutionScopes(parent).containsAll(resolutionScopes(child));
    }

    private static boolean ordinaryDependency(Declaration declaration) {
        if (declaration.entry() != null && !Set.of("module", "group", "name", "version", "version.ref")
                .containsAll(declaration.entry().fields().keySet())) return false;
        if (declaration.notation().equals("named") && !Set.of("group", "name", "version")
                .containsAll(fields(declaration.arguments()).keySet())) return false;
        boolean[] ordinary = {true};
        new JavaIsoVisitor<Integer>() {
            @Override public J.MethodInvocation visitMethodInvocation(J.MethodInvocation call, Integer p) {
                if (Set.of("artifact", "version", "strictly", "prefer", "require", "reject", "attributes", "capabilities")
                        .contains(call.getSimpleName())) ordinary[0] = false;
                return super.visitMethodInvocation(call, p);
            }
        }.visit(declaration.call(), 0);
        return ordinary[0];
    }

    private boolean protectedConfiguration(Script script, String configuration) {
        return script.declarations().stream().anyMatch(d -> GROUP.equals(d.group()) && PLATFORM.equals(d.artifact())
                && !Collections.disjoint(resolutionScopes(configuration), resolutionScopes(d.configuration())));
    }

    private List<Declaration> applicableBoms(Script script, String configuration) {
        return script.declarations().stream().filter(d -> GROUP.equals(d.group()) && BOM.equals(d.artifact())
                && !Collections.disjoint(resolutionScopes(configuration), resolutionScopes(d.configuration()))).toList();
    }

    private static boolean platformWrapper(Declaration d) {
        return d.call().getArguments().stream().anyMatch(a -> a instanceof J.MethodInvocation wrapper
                && Set.of("platform", "enforcedPlatform").contains(wrapper.getSimpleName()));
    }

    private void migrateGradleScript(Script script, Set<String> covered,
            Map<GradleOrmCatalog.Entry, List<Declaration>> consumers,
            Set<Declaration> managedConsumers, Plan plan, ExecutionContext ctx) {
        List<CoordinateSourceEdits.Edit> edits = plan.edits.computeIfAbsent(script.source().getSourcePath(), k -> new ArrayList<>());

        Map<String, List<Declaration>> configurations = new LinkedHashMap<>();
        for (Declaration d : script.declarations())
            configurations.computeIfAbsent(ormOnly() ? d.configuration() : d.block() == null ? "plugin" : d.block().getId() + ":" + d.configuration(), k -> new ArrayList<>()).add(d);

        List<List<Declaration>> ordered = new ArrayList<>(configurations.values());
        if (ormOnly()) ordered.sort(Comparator.comparingInt(ds -> (int) CONFIGURATIONS.stream()
                .filter(parent -> !parent.equals(ds.get(0).configuration()) && configurationInherits(ds.get(0).configuration(), parent)).count()));
        Set<String> addedBoms = new HashSet<>();
        for (List<Declaration> declarations : ordered) {
            String configuration = declarations.get(0).configuration();
            if (ormOnly() && protectedConfiguration(script, configuration)) {
                declarations.stream().map(Declaration::entry).filter(Objects::nonNull).forEach(plan.protectedEntries::add);
                continue;
            }
            List<Declaration> platforms = ormOnly() ? applicableBoms(script, configuration)
                    : declarations.stream().filter(d -> d.role().equals("platform")).toList();
            boolean conflict = platforms.size() > 1 || platforms.stream().anyMatch(d -> !d.supported() || !validVersion(d.version()));
            if (ormOnly()) conflict = platforms.size() > 1 || platforms.stream().anyMatch(d -> !d.supported()
                    || !getTargetVersion().equals(d.version()) || !platformWrapper(d)
                    || !configurationInherits(configuration, d.configuration()))
                    || script.source().printAll().contains("extendsFrom")
                    || script.declarations().stream().anyMatch(d -> d.role().equals("platform") && (!d.supported()
                            || Set.of(BOM, PLATFORM).contains(d.artifact()) && !GROUP.equals(d.group())));
            boolean addPlatform = false;

            for (Declaration d : declarations) {
                String artifact = target(d.group(), d.artifact());
                if (artifact == null) {
                    if (ormOnly()) report(this, skipped, script.source(), script.positions().get(d.call().getId()),
                            d.group() + ":" + d.artifact(), MANAGEMENT, "An ORM identity is unresolved; explicit version retained for " + BOM + ".", ctx);
                    continue;
                }
                boolean library = d.role().equals("library") && !TOOLING.contains(artifact);
                if (!library || (ormOnly() && !GROUP.equals(d.group()))) continue;
                boolean supported = !ormOnly() || d.supported() && ordinaryDependency(d) && !d.notation().equals("unsupported")
                        && (d.version().isEmpty() || simpleVersion(d.version()));
                boolean omit = !conflict && supported && covered.contains(artifact);
                if (!conflict && supported) addPlatform = true;

                int offset = script.positions().get(d.call().getId());
                if (d.entry() != null) {
                    if (omit) managedConsumers.add(d);
                    if (!omit) report(this, skipped, script.source(), offset, GROUP + ":" + artifact, MANAGEMENT,
                            (ormOnly() ? "Explicit version retained because " + BOM + " coverage could not be verified."
                                    : "Coordinates aligned; explicit version retained because platform coverage could not be verified."), ctx);
                    continue;
                }
                if (omit) {
                    String old = CoordinateSourceEdits.printed(script.source(), d.call());
                    String updated = omitVersion(script.source(), d, old);
                    if (!updated.equals(old)) edits.add(new CoordinateSourceEdits.Edit(offset, offset + old.length(), updated));
                }
                else {
                    report(this, skipped, script.source(), offset, GROUP + ":" + artifact, MANAGEMENT,
                            (ormOnly() ? "Explicit version retained because " + BOM + " coverage could not be verified."
                                    : "Coordinates aligned; explicit version retained because platform coverage could not be verified."), ctx);
                }
            }

            boolean inheritedAddition = ormOnly() && addedBoms.stream().anyMatch(parent -> configurationInherits(configuration, parent));
            if (addPlatform && platforms.isEmpty() && !inheritedAddition) {
                Declaration first = declarations.stream()
                        .filter(d -> d.role().equals("library") && d.supported() && GROUP.equals(d.group())).findFirst().orElse(null);
                if (first != null && first.block() != null) {
                    String block = CoordinateSourceEdits.printed(script.source(), first.block());
                    int close = script.positions().get(first.block().getId()) + block.lastIndexOf('}');
                    String newline = script.source().printAll().contains("\r\n") ? "\r\n" : "\n";
                    String before = script.source().printAll().substring(0, close);
                    int last = before.lastIndexOf('\n');
                    String closingIndent = last >= 0 ? before.substring(last + 1) : "";
                    if (!closingIndent.isBlank()) closingIndent = "";
                    String callPrefix = first.call().getPrefix().getWhitespace();
                    String indent = callPrefix.contains("\n") ? callPrefix.substring(callPrefix.lastIndexOf('\n') + 1) : "    ";
                    String inserted = first.configuration() + "(platform(\"" + GROUP + ":" + bomArtifact() + ":" + getTargetVersion() + "\"))";
                    String text = block.contains("\n") ? indent.substring(Math.min(indent.length(), closingIndent.length())) + inserted + newline + closingIndent
                            : "; " + inserted + " ";
                    edits.add(new CoordinateSourceEdits.Edit(close, close, text));
                    addedBoms.add(configuration);
                }
            }
        }
    }

    private static String omitVersion(SourceFile source, Declaration d, String old) {
        if (d.notation().equals("string") || d.notation().equals("extended")) {
            Expression argument = d.arguments().get(0);
            String coordinate = GROUP + ":" + d.artifact();
            return replacePart(old, CoordinateSourceEdits.printed(source, argument),
                    GradleOrmCatalog.quote(CoordinateSourceEdits.printed(source, argument), coordinate));
        }
        Map<String, Expression> fields = fields(d.arguments());
        if (fields.containsKey("version")) {
            Expression version = fields.get("version");
            String updated = old;
            Expression argument = d.arguments().stream().filter(a -> a instanceof G.MapEntry e && e.getValue() == version
                    || a instanceof J.Assignment assignment && assignment.getAssignment() == version).findFirst().orElseThrow();
            String printed = CoordinateSourceEdits.printed(source, argument);
            int index = updated.indexOf(printed);
            int start = index, end = index + printed.length();
            int comma = start - 1;
            while (comma >= 0 && Character.isWhitespace(updated.charAt(comma))) comma--;
            if (comma >= 0 && updated.charAt(comma) == ',') start = comma;
            else { while (end < updated.length() && Character.isWhitespace(updated.charAt(end))) end++; if (end < updated.length() && updated.charAt(end) == ',') end++; }
            return updated.substring(0, start) + retainedComments(source, argument) + updated.substring(end);
        }
        return old;
    }

    private static String replacePart(String source, String old, String value) {
        int offset = source.indexOf(old); if (offset < 0) throw new IllegalStateException("Missing structural argument: " + old);
        return source.substring(0, offset) + value + source.substring(offset + old.length());
    }

    private void migrateGradleCatalog(GradleOrmCatalog catalog, Set<GradleOrmCatalog.Entry> omitted, Plan plan, ExecutionContext ctx) {
        List<CoordinateSourceEdits.Edit> edits = plan.edits.computeIfAbsent(catalog.source.getSourcePath(), k -> new ArrayList<>());
        for (var entry : catalog.entries) {
            if (!GROUP.equals(entry.group()) || !CANONICAL.contains(entry.artifact())) continue;
            if (PLATFORM.equals(entry.artifact()) || BOM.equals(entry.artifact()) || TOOLING.contains(entry.artifact())) continue;
            if (ormOnly() && plan.protectedEntries.contains(entry)) continue;
            if (!omitted.contains(entry)) {
                int offset = catalog.positions.get(entry.tree().getId());
                report(this, skipped, catalog.source, offset, GROUP + ":" + entry.artifact(), MANAGEMENT,
                        "Catalog coordinates aligned; explicit version retained because complete consumer platform coverage could not be verified.", ctx);
                continue;
            }
            String old = CoordinateSourceEdits.printed(catalog.source, entry.tree());
            String updated = old;
            if (entry.tree().getValue() instanceof Toml.Literal) {
                updated = replacePart(old, CoordinateSourceEdits.printed(catalog.source, entry.tree().getValue()),
                        GradleOrmCatalog.quote(CoordinateSourceEdits.printed(catalog.source, entry.tree().getValue()), GROUP + ":" + entry.artifact()));
            }
            else {
                String field = entry.fields().containsKey("version.ref") ? "version.ref" : "version";
                if (entry.fields().containsKey(field)) {
                    String member = CoordinateSourceEdits.printed(catalog.source, entry.fields().get(field));
                    int start = updated.indexOf(member), end = start + member.length();
                    int comma = start - 1; while (comma >= 0 && Character.isWhitespace(updated.charAt(comma))) comma--;
                    if (comma >= 0 && updated.charAt(comma) == ',') start = comma;
                    else { while (end < updated.length() && Character.isWhitespace(updated.charAt(end))) end++; if (end < updated.length() && updated.charAt(end) == ',') end++; }
                    updated = updated.substring(0, start) + updated.substring(end);
                }
            }
            if (!updated.equals(old)) edits.add(new CoordinateSourceEdits.Edit(catalog.positions.get(entry.tree().getId()),
                    catalog.positions.get(entry.tree().getId()) + old.length(), updated));
        }
    }

    private static boolean completeConsumers(GradleOrmCatalog catalog, GradleOrmCatalog.Entry entry,
            List<Script> scripts, Plan plan) {
        Path catalogParent = catalog.source.getSourcePath().getParent();
        Path root = catalogParent == null ? Path.of("") : catalogParent.getParent();
        if (root == null) root = Path.of("");
        final Path buildRoot = root;
        SourceFile settings = plan.scripts.values().stream().filter(s -> s.getSourcePath().getFileName().toString().startsWith("settings")
                && Objects.equals(s.getSourcePath().getParent(), buildRoot.toString().isEmpty() ? null : buildRoot)).findFirst().orElse(null);
        if (settings == null) return false;
        Set<Path> required = new HashSet<>(); required.add(buildRoot);
        boolean[] unknown = {false};
        new JavaIsoVisitor<Integer>() {
            @Override public J.MethodInvocation visitMethodInvocation(J.MethodInvocation call, Integer p) {
                if (Set.of("include", "includeFlat", "includeBuild").contains(call.getSimpleName())) {
                    if (!call.getSimpleName().equals("include")) unknown[0] = true;
                    else for (Expression arg : call.getArguments()) {
                        if (arg instanceof J.Literal literal && literal.getValue() instanceof String name)
                            required.add(buildRoot.resolve(name.replaceFirst("^:", "").replace(':', '/')));
                        else unknown[0] = true;
                    }
                }
                return super.visitMethodInvocation(call, p);
            }
            @Override public J.Assignment visitAssignment(J.Assignment assignment, Integer p) {
                if (assignment.getVariable().printTrimmed().contains("projectDir")) unknown[0] = true;
                return super.visitAssignment(assignment, p);
            }
        }.visit(settings, 0);
        if (unknown[0]) return false;
        Set<Path> supplied = new HashSet<>();
        for (Script script : scripts)
            supplied.add(script.source().getSourcePath().getParent() == null ? Path.of("") : script.source().getSourcePath().getParent());
        if (!supplied.containsAll(required)) return false;
        for (Script script : scripts) {
            int[] reads = {0};
            new JavaIsoVisitor<Integer>() {
                @Override public J.FieldAccess visitFieldAccess(J.FieldAccess access, Integer p) {
                    String printed = access.printTrimmed();
                    for (String name : catalog.names) {
                        if (printed.equals(name + "." + entry.alias())) reads[0]++;
                        if (printed.startsWith(name + ".bundles.")) {
                            var members = catalog.bundles.getOrDefault(printed.substring((name + ".bundles.").length()), List.of());
                            if (members.stream().anyMatch(m -> GradleOrmCatalog.accessor(m).equals(entry.alias()))) reads[0]++;
                        }
                    }
                    return super.visitFieldAccess(access, p);
                }
            }.visit(script.source(), 0);
            long recognized = script.declarations().stream().filter(d -> entry.equals(d.entry())).count();
            if (reads[0] != recognized) return false;
        }
        return true;
    }
}
