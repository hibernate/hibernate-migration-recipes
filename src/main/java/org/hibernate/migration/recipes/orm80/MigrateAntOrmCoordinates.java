package org.hibernate.migration.recipes.orm80;

import org.hibernate.migration.recipes.table.SkippedMigrations;
import org.hibernate.migration.recipes.xml.DescriptorVisitor;
import org.jspecify.annotations.NonNull;
import org.openrewrite.*;
import org.openrewrite.xml.XmlIsoVisitor;
import org.openrewrite.xml.tree.Xml;
import java.util.*;

import static org.hibernate.migration.recipes.orm80.OrmCoordinateSupport.*;

/// Migrates Ivy revisions and recognized inline Maven Ant Tasks dependencies.
///
/// @author Steve Ebersole
public class MigrateAntOrmCoordinates extends Recipe {
    @Option(displayName = "Target ORM version", description = "An exact published ORM 8.0 release.", example = "8.0.0.Beta3")
    private final String targetVersion;
    private final transient SkippedMigrations skipped = new SkippedMigrations(this);
    private static final String ANT_TASKS = "antlib:org.apache.maven.artifact.ant";
    public MigrateAntOrmCoordinates(String targetVersion) { this.targetVersion = targetVersion; }
    public String getTargetVersion() { return targetVersion; }
    // Relocated coordinates can expose declarations to earlier aggregate recipes.
    @Override public boolean causesAnotherCycle() { return true; }
    @Override public @NonNull String getDisplayName() { return "Migrate Ant ORM coordinates"; }
    @Override public @NonNull String getDescription() { return "Relocates allowlisted Ivy and Maven Ant Tasks declarations and preserves explicit target revisions."; }
    @Override public @NonNull Validated<Object> validate() { return super.validate().and(OrmCoordinateSupport.validate(targetVersion)); }
    private record Candidate(Xml.Tag tag, boolean ivy, boolean supported, String group, String artifact, String version, String reason) {}

    @Override public @NonNull TreeVisitor<?, ExecutionContext> getVisitor() {
        return new XmlIsoVisitor<ExecutionContext>() {
            @Override public Xml.@NonNull Document visitDocument(Xml.@NonNull Document doc, @NonNull ExecutionContext ctx) {
                if (!validate().isValid()) return doc;
                String root = local(doc.getRoot());
                if (!Set.of("ivy-module", "project").contains(root)) return doc;
                Set<UUID> processed = ctx.computeMessageIfAbsent(getName() + ".processed:" + targetVersion, k -> new HashSet<>());
                if (!processed.add(doc.getId())) return doc;
                boolean ivy = root.equals("ivy-module");
                Map<String, String> aliases = new HashMap<>(); Set<String> conflicts = new HashSet<>();
                collectDefinitions(doc.getRoot(), "", aliases, conflicts);
                List<Candidate> candidates = new ArrayList<>();
                MigrateAntOrmCoordinates.collect(doc.getRoot(), new ArrayList<>(), Map.of("", ""), ivy, aliases, conflicts, candidates);
                Set<UUID> ids = new HashSet<>(); candidates.forEach(c -> ids.add(c.tag.getId()));
                Map<UUID, Integer> positions = EnhancementMigrationSupport.offsets(doc, ids);
                Map<UUID, Xml.Tag> edits = new HashMap<>();
                Map<String, Xml.Tag> properties = new HashMap<>();
                Set<String> localVersions = new HashSet<>();
                Map<String, Integer> propertyCounts = new HashMap<>();
                if (!ivy) for (Xml.Tag property : children(doc.getRoot(), "property")) {
                    propertyCounts.merge("${" + attribute(property, "name") + "}", 1, Integer::sum);
                    if (simpleVersion(attribute(property, "value")) && attribute(property, "file").isEmpty())
                        localVersions.add("${" + attribute(property, "name") + "}");
                }
                localVersions.removeIf(reference -> propertyCounts.getOrDefault(reference, 0) != 1);
                if (!ivy && children(doc.getRoot(), "import").isEmpty()) {
                    for (Xml.Tag property : children(doc.getRoot(), "property")) {
                        String reference = "${" + attribute(property, "name") + "}";
                        List<Candidate> uses = candidates.stream().filter(c -> c.version.equals(reference)).toList();
                        if (!uses.isEmpty() && localVersions.contains(reference) && simpleVersion(attribute(property, "value")) && attribute(property, "file").isEmpty()
                                && referenceCount(doc.getRoot(), reference) == uses.size()
                                && uses.stream().allMatch(c -> c.supported && c.reason == null && target(c.group, c.artifact) != null)) properties.put(reference, property);
                    }
                }
                Set<UUID> redundant = new HashSet<>(), entityConflicts = new HashSet<>();
                for (Candidate entity : candidates) if (entityManager(entity.artifact)) {
                    List<Candidate> cores = candidates.stream().filter(c -> c.artifact.equals("hibernate-core") && target(c.group, c.artifact) != null
                            && owner(doc.getRoot(), c.tag.getId()).equals(owner(doc.getRoot(), entity.tag.getId()))).toList();
                    if (cores.isEmpty()) continue;
                    Candidate core = cores.get(0);
                    if (cores.size() == 1 && entity.supported && core.supported && entity.reason == null && core.reason == null
                            && (simpleVersion(entity.version) || entity.version.isEmpty() || localVersions.contains(entity.version))
                            && (simpleVersion(core.version) || core.version.isEmpty() || localVersions.contains(core.version))
                            && CoordinateXmlEdits.dependencyBehavior(entity.tag, ivy, true).equals(CoordinateXmlEdits.dependencyBehavior(core.tag, ivy, true))) redundant.add(entity.tag.getId());
                    else { entityConflicts.add(entity.tag.getId()); cores.forEach(c -> entityConflicts.add(c.tag.getId())); }
                }
                Map<String, Integer> counts = new HashMap<>();
                candidates.forEach(c -> {
                    String target = target(c.group, c.artifact);
                    if (target != null && !redundant.contains(c.tag.getId())) counts.merge(owner(doc.getRoot(), c.tag.getId()) + ":" + target + ":" + attribute(c.tag, "classifier"), 1, Integer::sum);
                });
                properties.keySet().removeIf(reference -> candidates.stream().anyMatch(c -> c.version.equals(reference)
                        && (entityConflicts.contains(c.tag.getId()) || counts.getOrDefault(owner(doc.getRoot(), c.tag.getId()) + ":" + target(c.group, c.artifact) + ":" + attribute(c.tag, "classifier"), 0) > 1)));
                for (Candidate c : candidates) {
                    if (redundant.contains(c.tag.getId())) continue;
                    String artifact = target(c.group, c.artifact);
                    String reason = c.reason;
                    if (c.group.isEmpty() || c.group.contains("${") || c.artifact.contains("${")) reason = IDENTITY;
                    else if (artifact == null) reason = ARTIFACT;
                    else if (entityConflicts.contains(c.tag.getId()) || counts.getOrDefault(owner(doc.getRoot(), c.tag.getId()) + ":" + artifact + ":" + attribute(c.tag, "classifier"), 0) > 1) reason = CONFLICT;
                    else if (!c.version.isEmpty() && !simpleVersion(c.version) && !localVersions.contains(c.version)) reason = VERSION;
                    else if (reason == null && !c.supported) reason = SYNTAX;
                    if (reason != null) {
                        report(MigrateAntOrmCoordinates.this, skipped, doc, positions.get(c.tag.getId()), c.group + ":" + c.artifact,
                                reason, "Resolve this Ant/Ivy declaration manually before migration.", ctx); continue;
                    }
                    Xml.Tag updated = CoordinateXmlEdits.attribute(c.tag, c.ivy ? "org" : "groupId", GROUP);
                    updated = CoordinateXmlEdits.attribute(updated, c.ivy ? "name" : "artifactId", artifact);
                    updated = CoordinateXmlEdits.attribute(updated, c.ivy ? "rev" : "version", properties.containsKey(c.version) ? c.version : targetVersion);
                    if (c.ivy && !attribute(updated, "revConstraint").isEmpty()) updated = CoordinateXmlEdits.attribute(updated, "revConstraint", targetVersion);
                    if (!c.ivy && attribute(c.tag, "version").isEmpty()) {
                        // Parse an attribute with the existing quotation convention rather than inventing tree padding.
                        String quote = c.tag.getAttributes().isEmpty() ? "\"" : (c.tag.getAttributes().get(0).getValue().getQuote() == Xml.Attribute.Value.Quote.Single ? "'" : "\"");
                        Xml.Attribute version = Xml.Tag.build("<d version=" + quote + targetVersion + quote + "/>").getAttributes().get(0);
                        List<Xml.Attribute> attributes = new ArrayList<>(updated.getAttributes()); attributes.add(version); updated = updated.withAttributes(attributes);
                    }
                    edits.put(c.tag.getId(), updated);
                    if (properties.containsKey(c.version)) {
                        Xml.Tag property = properties.get(c.version);
                        edits.put(property.getId(), CoordinateXmlEdits.attribute(property, "value", targetVersion));
                    }
                }
                Xml.Document result = (Xml.Document) new XmlIsoVisitor<ExecutionContext>() {
                    @Override public Xml.@NonNull Tag visitTag(Xml.@NonNull Tag tag, @NonNull ExecutionContext p) {
                        return CoordinateXmlEdits.removeDeclarations(super.visitTag(edits.getOrDefault(tag.getId(), tag), p), redundant);
                    }
                }.visitNonNull(doc, ctx);
                return result.printAll().equals(doc.printAll()) ? doc : result;
            }
        };
    }
    private static int referenceCount(Xml.Tag tag, String reference) {
        int count = 0;
        for (Xml.Attribute a : tag.getAttributes()) if (a.getValueAsString().contains(reference)) count++;
        if (tag.getValue().filter(v -> v.contains(reference)).isPresent()) count++;
        for (Xml.Tag child : tag.getChildren()) count += referenceCount(child, reference);
        return count;
    }
    private static String owner(Xml.Tag root, UUID id) {
        for (Xml.Tag child : root.getChildren()) {
            if (child.getId().equals(id)) return root.getId().toString();
            String result = owner(child, id); if (!result.isEmpty()) return result;
        }
        return "";
    }
    private static void collectDefinitions(Xml.Tag tag, String path, Map<String, String> aliases, Set<String> conflicts) {
        String here = path + "/" + local(tag);
        if (local(tag).equals("taskdef")) {
            String name = attribute(tag, "name"), classname = attribute(tag, "classname");
            if (!name.isEmpty()) {
                if (!here.equals("/project/taskdef") || aliases.putIfAbsent(name, classname) != null) conflicts.add(name);
                if (!"org.apache.maven.artifact.ant.DependenciesTask".equals(classname)) conflicts.add(name);
            }
        }
        for (Xml.Tag child : tag.getChildren()) collectDefinitions(child, here, aliases, conflicts);
    }
    private static void collect(Xml.Tag tag, List<Xml.Tag> ancestors, Map<String, String> inherited, boolean ivy,
            Map<String, String> aliases, Set<String> conflicts, List<Candidate> candidates) {
        Map<String, String> ns = DescriptorVisitor.namespaces(tag, inherited);
        List<Xml.Tag> path = new ArrayList<>(ancestors); path.add(tag);
        if (local(tag).equals("dependency")) {
            String group = attribute(tag, ivy ? "org" : "groupId"), artifact = attribute(tag, ivy ? "name" : "artifactId");
            if (recognizable(group, artifact) || knownArtifact(artifact) && (group.contains("${") || group.isEmpty())) {
                boolean supported; String reason = null;
                if (ivy) supported = path.size() == 3 && local(path.get(1)).equals("dependencies")
                        && DescriptorVisitor.namespace(tag, ns).isEmpty() && attribute(tag, "branch").isEmpty()
                        && attribute(tag, "branchConstraint").isEmpty() && attribute(tag, "rev").length() > 0
                        && tag.getChildren().stream().noneMatch(t -> local(t).equals("artifact"));
                else {
                    Xml.Tag task = ancestors.isEmpty() ? null : ancestors.get(ancestors.size() - 1);
                    String taskName = task == null ? "" : task.getName();
                    Map<String, String> taskNs = new HashMap<>(ns);
                    boolean namespaceTask = task != null && local(task).equals("dependencies") && ANT_TASKS.equals(DescriptorVisitor.namespace(task, taskNs));
                    boolean alias = aliases.containsKey(taskName) && !conflicts.contains(taskName);
                    boolean blockedScope = ancestors.stream().anyMatch(t -> Set.of("macrodef", "presetdef", "sequential").contains(local(t))
                            || local(t).equals("target") && (!attribute(t, "if").isEmpty() || !attribute(t, "unless").isEmpty()));
                    supported = (namespaceTask || alias) && !blockedScope && !"system".equals(attribute(tag, "scope"))
                            && children(tag, "systemPath").isEmpty();
                    if (!namespaceTask && !alias) reason = conflicts.contains(taskName) ? CONFLICT : IDENTITY;
                }
                candidates.add(new Candidate(tag, ivy, supported, group, artifact, attribute(tag, ivy ? "rev" : "version"), reason));
            }
        }
        for (Xml.Tag child : tag.getChildren()) collect(child, path, ns, ivy, aliases, conflicts, candidates);
    }
}
