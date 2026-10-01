package org.hibernate.migration.recipes.orm80;

import org.hibernate.migration.recipes.table.SkippedMigrations;
import org.hibernate.migration.recipes.xml.DescriptorVisitor;
import org.jspecify.annotations.NonNull;
import org.openrewrite.*;
import org.openrewrite.maven.tree.MavenRepository;
import org.openrewrite.maven.tree.GroupArtifactVersion;
import org.openrewrite.maven.internal.MavenPomDownloader;
import org.openrewrite.maven.MavenDownloadingException;
import org.openrewrite.xml.XmlIsoVisitor;
import org.openrewrite.xml.tree.Xml;
import java.util.*;
import java.util.regex.*;

import static org.hibernate.migration.recipes.orm80.OrmCoordinateSupport.*;

/// Relocates Maven ORM dependencies and tooling, with verified BOM-based version omission.
///
/// @author Steve Ebersole
public class MigrateMavenOrmCoordinates extends Recipe {
    @Option(displayName = "Target ORM version", description = "An exact published ORM 8.0 release.", example = "8.0.0.Beta3")
    private final String targetVersion;
    private final transient SkippedMigrations skipped = new SkippedMigrations(this);
    public MigrateMavenOrmCoordinates(String targetVersion) { this.targetVersion = targetVersion; }
    public String getTargetVersion() { return targetVersion; }
    // Relocated coordinates can expose declarations to earlier aggregate recipes.
    @Override public boolean causesAnotherCycle() { return true; }
    @Override public @NonNull String getDisplayName() { return "Migrate Maven ORM coordinates"; }
    @Override public @NonNull String getDescription() { return "Aligns allowlisted Maven ORM dependencies and tooling and imports the Hibernate platform."; }
    @Override public @NonNull Validated<Object> validate() { return super.validate().and(OrmCoordinateSupport.validate(targetVersion)); }
    private record Candidate(Xml.Tag tag, Xml.Tag scope, String role, String group, String artifact, String version, boolean namespaceSafe) {}

    @Override public @NonNull TreeVisitor<?, ExecutionContext> getVisitor() {
        return new XmlIsoVisitor<ExecutionContext>() {
            @Override public Xml.@NonNull Document visitDocument(Xml.@NonNull Document doc, @NonNull ExecutionContext ctx) {
                if (!validate().isValid()) return doc;
                String ns = DescriptorVisitor.namespace(doc.getRoot(), DescriptorVisitor.namespaces(doc.getRoot(), Map.of("", "")));
                if (!"project".equals(local(doc.getRoot())) || !(ns.isEmpty() || "http://maven.apache.org/POM/4.0.0".equals(ns))) return doc;
                return migrate(doc, ns, ctx);
            }
        };
    }

    private Xml.Document migrate(Xml.Document doc, String ns, ExecutionContext ctx) {
        Map<String, String> properties = new HashMap<>();
        children(doc.getRoot(), "properties").forEach(t -> t.getChildren().forEach(p -> properties.put(local(p), p.getValue().orElse(""))));
        List<Candidate> candidates = new ArrayList<>();
        collect(doc.getRoot(), "", doc.getRoot(), ns, Map.of("", ""), properties, candidates, null);
        if (candidates.isEmpty()) return doc;
        Set<UUID> processed = ctx.computeMessageIfAbsent(getName() + ".processed:" + targetVersion, k -> new HashSet<>());
        if (!processed.add(doc.getId())) return doc;
        Set<UUID> ids = new HashSet<>(); candidates.forEach(c -> ids.add(c.tag.getId()));
        Map<UUID, Integer> positions = EnhancementMigrationSupport.offsets(doc, ids);
        Map<UUID, Xml.Tag> replacements = new HashMap<>();
        Map<String, Xml.Tag> safeProperties = new HashMap<>();
        if (children(doc.getRoot(), "parent").isEmpty() && children(doc.getRoot(), "modules").isEmpty()) {
            for (Xml.Tag container : children(doc.getRoot(), "properties")) for (Xml.Tag property : container.getChildren()) {
                String reference = "${" + local(property) + "}";
                List<Candidate> consumers = candidates.stream().filter(c -> value(c.tag, "version").equals(reference)).toList();
                if (!consumers.isEmpty() && simpleVersion(property.getValue().orElse("")) && referenceCount(doc.getRoot(), reference) == consumers.size()
                        && consumers.stream().allMatch(c -> target(c.group, c.artifact) != null && c.namespaceSafe
                                && children(c.tag, "groupId").size() == 1 && children(c.tag, "artifactId").size() == 1
                                && candidates.stream().noneMatch(other -> other != c && collision(other, doc.getRoot()).equals(collision(c, doc.getRoot()))))) safeProperties.put(reference, property);
            }
        }
        Map<UUID, List<Candidate>> scopes = new LinkedHashMap<>();
        for (Candidate c : candidates) scopes.computeIfAbsent(c.scope.getId(), k -> new ArrayList<>()).add(c);
        List<MavenRepository> repositories = new ArrayList<>();
        children(doc.getRoot(), "repositories").forEach(t -> children(t, "repository").forEach(r -> {
            String url = value(r, "url"); if (!url.isBlank() && !url.contains("${"))
                repositories.add(MavenRepository.builder().id(value(r, "id")).uri(url).releases("true").snapshots("true").build());
        }));
        Set<UUID> redundant = new HashSet<>(), entityConflicts = new HashSet<>();
        for (Candidate entity : candidates) if (entityManager(entity.artifact)) {
            List<Candidate> cores = candidates.stream().filter(c -> c.artifact.equals("hibernate-core")
                    && target(c.group, c.artifact) != null && c.role.equals(entity.role)
                    && owner(doc.getRoot(), c.tag.getId()).equals(owner(doc.getRoot(), entity.tag.getId()))).toList();
            if (cores.isEmpty()) continue;
            Candidate core = cores.get(0);
            if (cores.size() == 1 && entity.namespaceSafe && core.namespaceSafe
                    && (entity.version.isEmpty() || simpleVersion(entity.version)) && (core.version.isEmpty() || simpleVersion(core.version))
                    && CoordinateXmlEdits.dependencyBehavior(entity.tag, false, false).equals(CoordinateXmlEdits.dependencyBehavior(core.tag, false, false))) redundant.add(entity.tag.getId());
            else { entityConflicts.add(entity.tag.getId()); cores.forEach(c -> entityConflicts.add(c.tag.getId())); }
        }
        safeProperties.keySet().removeIf(reference -> candidates.stream().anyMatch(c -> entityConflicts.contains(c.tag.getId()) && value(c.tag, "version").equals(reference)));
        Set<String> covered = candidates.stream().anyMatch(c -> c.role.equals("library"))
                ? managed(targetVersion, repositories, ctx) : Set.of();
        for (List<Candidate> scope : scopes.values()) {
            List<Candidate> platforms = scope.stream().filter(c -> PLATFORM.equals(c.artifact)).toList();
            boolean platformConflict = platforms.size() > 1 || platforms.stream().anyMatch(c -> !c.role.equals("management")
                    || !"pom".equals(value(c.tag, "type")) || !"import".equals(value(c.tag, "scope")));
            Set<String> duplicate = new HashSet<>();
            Map<String, Integer> counts = new HashMap<>();
            for (Candidate c : scope) {
                String artifact = target(c.group, c.artifact);
                if (artifact != null && !PLATFORM.equals(artifact) && !redundant.contains(c.tag.getId())) {
                    String key = collision(c, doc.getRoot());
                    if (counts.merge(key, 1, Integer::sum) > 1) duplicate.add(key);
                }
            }
            boolean libraries = false;
            boolean effective = !platformConflict && children(doc.getRoot(), "parent").isEmpty()
                    && otherImportsClear(scope.get(0).scope, properties, repositories, covered, ctx);
            for (Candidate c : scope) {
                if (redundant.contains(c.tag.getId())) continue;
                String target = target(c.group, c.artifact);
                String reason = null;
                if (c.group.isEmpty() || c.group.contains("${") || c.artifact.contains("${")) reason = IDENTITY;
                else if (target == null) reason = ARTIFACT;
                else if (entityConflicts.contains(c.tag.getId()) || platformConflict && PLATFORM.equals(target)
                        || duplicate.contains(collision(c, doc.getRoot()))) reason = CONFLICT;
                else if (!c.version.isEmpty() && !simpleVersion(c.version) && !(simpleReference(value(c.tag, "version")) && simpleVersion(resolve(value(c.tag, "version"), properties)))) reason = VERSION;
                else if (!c.namespaceSafe || children(c.tag, "groupId").size() > 1 || children(c.tag, "artifactId").size() != 1 || children(c.tag, "version").size() > 1) reason = SYNTAX;
                if (reason != null) {
                    report(MigrateMavenOrmCoordinates.this, skipped, doc, positions.get(c.tag.getId()), c.group + ":" + c.artifact,
                            reason, "Resolve this Maven declaration manually before migration.", ctx); continue;
                }
                boolean omit = c.role.equals("library") && !TOOLING.contains(target) && effective && covered.contains(target)
                        && value(c.tag, "classifier").isEmpty() && (value(c.tag, "type").isEmpty() || value(c.tag, "type").equals("jar"));
                Xml.Tag updated = CoordinateXmlEdits.set(CoordinateXmlEdits.set(c.tag, "groupId", GROUP), "artifactId", target);
                updated = omit ? CoordinateXmlEdits.remove(updated, "version") : CoordinateXmlEdits.set(updated, "version", safeProperties.containsKey(value(c.tag, "version")) ? value(c.tag, "version") : targetVersion);
                if (!omit && safeProperties.containsKey(value(c.tag, "version"))) {
                    Xml.Tag property = safeProperties.get(value(c.tag, "version"));
                    replacements.put(property.getId(), property.withValue(targetVersion));
                }
                replacements.put(c.tag.getId(), updated);
                if (c.role.equals("library") && !TOOLING.contains(target)) libraries = true;
                if (c.role.equals("library") && !TOOLING.contains(target) && !omit && value(c.tag, "classifier").isEmpty()
                        && (value(c.tag, "type").isEmpty() || value(c.tag, "type").equals("jar")))
                    report(this, skipped, doc, positions.get(c.tag.getId()), GROUP + ":" + target, MANAGEMENT,
                            "Coordinates aligned; explicit version retained because effective platform management could not be verified.", ctx);
            }
            if (libraries && !platformConflict && platforms.isEmpty()) {
                Xml.Tag scopeTag = scope.get(0).scope;
                Xml.Tag bom = Xml.Tag.build("<" + name(scopeTag, "dependency") + "><" + name(scopeTag, "groupId") + ">" + GROUP
                        + "</" + name(scopeTag, "groupId") + "><" + name(scopeTag, "artifactId") + ">" + PLATFORM
                        + "</" + name(scopeTag, "artifactId") + "><" + name(scopeTag, "version") + ">" + targetVersion
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
        Xml.Document result = (Xml.Document) new XmlIsoVisitor<ExecutionContext>() {
            @Override public Xml.@NonNull Tag visitTag(Xml.@NonNull Tag tag, @NonNull ExecutionContext p) {
                return CoordinateXmlEdits.removeDeclarations(super.visitTag(replacements.getOrDefault(tag.getId(), tag), p), redundant);
            }
        }.visitNonNull(doc, ctx);
        return result.printAll().equals(doc.printAll()) ? doc : result;
    }

    private boolean otherImportsClear(Xml.Tag scope, Map<String, String> props, List<MavenRepository> repositories, Set<String> covered, ExecutionContext ctx) {
        for (Xml.Tag dm : children(scope, "dependencyManagement")) for (Xml.Tag dependencies : children(dm, "dependencies"))
            for (Xml.Tag d : children(dependencies, "dependency")) {
                if (!"import".equals(value(d, "scope")) || PLATFORM.equals(value(d, "artifactId"))) continue;
                String group = resolve(value(d, "groupId"), props), artifact = resolve(value(d, "artifactId"), props), version = resolve(value(d, "version"), props);
                if (group.isBlank() || artifact.isBlank() || !simpleVersion(version)) return false;
                try {
                    var downloader = new MavenPomDownloader(ctx);
                    var other = downloader.download(new GroupArtifactVersion(group, artifact, version), null, null,
                            repositories.isEmpty() ? List.of(MavenRepository.MAVEN_CENTRAL) : repositories).resolve(List.of(), downloader, ctx);
                    for (String orm : covered) {
                        String managedVersion = other.getManagedVersion(GROUP, orm, "jar", null);
                        if (managedVersion != null && !managedVersion.equals(targetVersion)) return false;
                    }
                }
                catch (MavenDownloadingException e) { return false; }
            }
        return true;
    }
    private static int referenceCount(Xml.Tag tag, String reference) {
        int count = tag.getValue().filter(v -> v.contains(reference)).isPresent() ? 1 : 0;
        for (Xml.Tag child : tag.getChildren()) count += referenceCount(child, reference);
        for (Xml.Attribute attribute : tag.getAttributes()) if (attribute.getValueAsString().contains(reference)) count++;
        return count;
    }
    private static boolean simpleReference(String value) { return value.matches("\\$\\{[A-Za-z0-9_.-]+}"); }
    private static String resolve(String value, Map<String, String> props) {
        return simpleReference(value) ? props.getOrDefault(value.substring(2, value.length() - 1), value) : value;
    }
    private static String collision(Candidate c, Xml.Tag root) {
        String type = value(c.tag, "type");
        return c.role + ":" + target(c.group, c.artifact) + ":" + value(c.tag, "classifier") + ":" + (type.isEmpty() ? "jar" : type)
                + ":" + owner(root, c.tag.getId());
    }
    private static String owner(Xml.Tag root, UUID id) {
        for (Xml.Tag child : root.getChildren()) {
            if (child.getId().equals(id)) return root.getId().toString();
            String result = owner(child, id); if (!result.isEmpty()) return result;
        }
        return "";
    }
    private static String childValue(Xml.Tag tag, String name, String namespace, Map<String, String> ns) {
        List<Xml.Tag> values = tag.getChildren().stream().filter(t -> local(t).equals(name)
                && namespace.equals(DescriptorVisitor.namespace(t, DescriptorVisitor.namespaces(t, ns)))).toList();
        return values.size() == 1 ? values.get(0).getValue().orElse("").trim() : "";
    }
    private static void collect(Xml.Tag tag, String path, Xml.Tag scope, String namespace, Map<String, String> inherited,
            Map<String, String> props, List<Candidate> candidates, Xml.Tag plugin) {
        Map<String, String> ns = DescriptorVisitor.namespaces(tag, inherited);
        if (!namespace.equals(DescriptorVisitor.namespace(tag, ns))) return;
        String here = path.isEmpty() ? local(tag) : path + "/" + local(tag);
        if (here.equals("project/profiles/profile")) scope = tag;
        String relative = here.startsWith("project/profiles/profile/") ? "project/" + here.substring("project/profiles/profile/".length()) : here;
        String role = switch (relative) {
            case "project/dependencies/dependency" -> "library";
            case "project/dependencyManagement/dependencies/dependency" -> "management";
            case "project/build/plugins/plugin", "project/build/pluginManagement/plugins/plugin" -> "plugin";
            default -> "";
        };
        if (relative.matches("project/build/(?:pluginManagement/)?plugins/plugin/dependencies/dependency")) role = "pluginDependency";
        if (relative.matches("project/build/(?:pluginManagement/)?plugins/plugin/(?:executions/execution/)?configuration/annotationProcessorPaths/path")) {
            // Only compiler plugin processor-path configuration is recognized.
            role = "processor";
        }
        if (role.equals("plugin")) plugin = tag;
        if (role.equals("processor") && (plugin == null || !value(plugin, "artifactId").equals("maven-compiler-plugin")
                || !(value(plugin, "groupId").isEmpty() || value(plugin, "groupId").equals("org.apache.maven.plugins")))) role = "";
        if (!role.isEmpty()) {
            String group = resolve(childValue(tag, "groupId", namespace, ns), props), artifact = resolve(childValue(tag, "artifactId", namespace, ns), props);
            if (recognizable(group, artifact) || (knownArtifact(artifact) && (group.contains("${") || group.isEmpty())))
                candidates.add(new Candidate(tag, scope, role, group, artifact, resolve(childValue(tag, "version", namespace, ns), props),
                        tag.getChildren().stream().noneMatch(t -> Set.of("groupId", "artifactId", "version", "type", "classifier", "scope").contains(local(t))
                                && !namespace.equals(DescriptorVisitor.namespace(t, DescriptorVisitor.namespaces(t, ns))))));
        }
        for (Xml.Tag child : tag.getChildren()) collect(child, here, scope, namespace, ns, props, candidates, plugin);
    }
}
