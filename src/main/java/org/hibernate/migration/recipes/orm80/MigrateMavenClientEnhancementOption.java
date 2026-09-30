package org.hibernate.migration.recipes.orm80;

import org.hibernate.migration.recipes.table.SkippedMigrations;
import org.hibernate.migration.recipes.xml.DescriptorVisitor;
import org.jspecify.annotations.NonNull;
import org.openrewrite.*;
import org.openrewrite.xml.XmlIsoVisitor;
import org.openrewrite.xml.tree.Content;
import org.openrewrite.xml.tree.Xml;

import java.util.*;

import static org.hibernate.migration.recipes.orm80.EnhancementMigrationSupport.*;

/// Migrates explicit Hibernate Maven enhancement configuration with bounded inheritance handling.
///
/// @author Steve Ebersole
public class MigrateMavenClientEnhancementOption extends Recipe {
    private final transient SkippedMigrations skipped = new SkippedMigrations(this);
    private static final Set<String> PLUGINS = Set.of("org.hibernate.orm:hibernate-maven-plugin",
            "org.hibernate.orm:hibernate-enhance-maven-plugin", "org.hibernate:hibernate-enhance-maven-plugin");

    @Override public @NonNull String getDisplayName() { return "Migrate Maven client enhancement option"; }
    @Override public @NonNull String getDescription() {
        return "Renames Hibernate enhancement configuration while preserving explicit client values and reporting unresolved inheritance.";
    }
    @Override public @NonNull TreeVisitor<?, ExecutionContext> getVisitor() {
        return new XmlIsoVisitor<ExecutionContext>() {
            @Override public Xml.@NonNull Document visitDocument(Xml.@NonNull Document doc, @NonNull ExecutionContext ctx) {
                String ns = DescriptorVisitor.namespace(doc.getRoot(), DescriptorVisitor.namespaces(doc.getRoot(), Map.of("", "")));
                if (!"project".equals(DescriptorVisitor.local(doc.getRoot()))
                        || !("".equals(ns) || "http://maven.apache.org/POM/4.0.0".equals(ns))) return doc;
                List<Configuration> configurations = new ArrayList<>();
                MigrateMavenClientEnhancementOption.collect(doc.getRoot(), new ArrayList<>(), Map.of("", ""), ns, configurations);
                Set<UUID> candidates = new HashSet<>();
                configurations.forEach(c -> c.old.forEach(t -> candidates.add(t.getId())));
                Map<UUID, Integer> positions = offsets(doc, candidates);
                Set<UUID> renames = new HashSet<>(), removals = new HashSet<>();
                boolean parent = children(doc.getRoot(), "parent", ns, Map.of("", "")).size() > 0;
                for (Configuration c : configurations) {
                    if (c.old.isEmpty()) continue;
                    String reason = null, message = null;
                    if (c.identity == null) {
                        reason = IDENTITY; message = "The Hibernate plugin group is unresolved; establish its identity manually.";
                    }
                    else if (c.old.size() > 1 || c.fresh.size() > 1 || c.merge) {
                        reason = AMBIGUOUS; message = "Duplicate options or Maven merge directives require manual resolution.";
                    }
                    else if (c.fresh.isEmpty() && (parent || configurations.stream()
                            .anyMatch(other -> c.identity.equals(other.identity) && other != c && !other.fresh.isEmpty()))) {
                        reason = INHERITANCE; message = "A parent or another plugin configuration scope could supply the client option; resolve inheritance manually.";
                    }
                    else if (c.old.stream().anyMatch(t -> !t.getChildren().isEmpty())
                            || c.fresh.stream().anyMatch(t -> !t.getChildren().isEmpty())) {
                        reason = SYNTAX; message = "Nested enhancement option content is unsupported; migrate it manually.";
                    }
                    if (reason != null) {
                        for (Xml.Tag old : c.old) report(MigrateMavenClientEnhancementOption.this, skipped, doc,
                                positions, old.getId(), reason, message, ctx);
                    }
                    else if (c.fresh.isEmpty()) renames.add(c.old.get(0).getId());
                    else removals.add(c.old.get(0).getId());
                }
                if (renames.isEmpty() && removals.isEmpty()) return doc;
                return (Xml.Document) new XmlIsoVisitor<ExecutionContext>() {
                    @Override public Xml.@NonNull Tag visitTag(Xml.@NonNull Tag tag, @NonNull ExecutionContext context) {
                        Xml.Tag result = super.visitTag(tag, context);
                        if (renames.contains(tag.getId())) result = result.withName(DescriptorVisitor.renamed(tag, NEW));
                        if (result.getContent() != null && result.getContent().stream().anyMatch(c -> removals.contains(c.getId()))) {
                            List<Content> content = new ArrayList<>();
                            for (Content child : result.getContent()) {
                                if (!removals.contains(child.getId())) content.add(child);
                                else if (child instanceof Xml.Tag old && old.getContent() != null) {
                                    for (Content nested : old.getContent()) {
                                        if (nested instanceof Xml.Comment comment)
                                            content.add(comment.withPrefix(old.getPrefix() + comment.getPrefix()));
                                    }
                                }
                            }
                            result = result.withContent(content);
                        }
                        return result;
                    }
                }.visitNonNull(doc, ctx);
            }
        };
    }

    private record Configuration(String identity, List<Xml.Tag> old, List<Xml.Tag> fresh, boolean merge) {}

    private static void collect(Xml.Tag tag, List<Xml.Tag> ancestors, Map<String, String> inherited,
            String namespace, List<Configuration> configurations) {
        Map<String, String> scope = DescriptorVisitor.namespaces(tag, inherited);
        if (!namespace.equals(DescriptorVisitor.namespace(tag, scope))) return;
        List<Xml.Tag> path = new ArrayList<>(ancestors);
        path.add(tag);
        if ("plugin".equals(DescriptorVisitor.local(tag)) && pluginPath(path)) {
            String artifact = childValue(tag, "artifactId", namespace, scope);
            String group = childValue(tag, "groupId", namespace, scope);
            String identity = group + ":" + artifact;
            if (PLUGINS.contains(identity) || (Set.of("hibernate-maven-plugin", "hibernate-enhance-maven-plugin").contains(artifact)
                    && (group.isEmpty() || group.contains("${")))) {
                String resolved = PLUGINS.contains(identity) ? identity : null;
                for (Xml.Tag config : children(tag, "configuration", namespace, scope))
                    add(config, resolved, path, scope, namespace, configurations);
                for (Xml.Tag executions : children(tag, "executions", namespace, scope)) {
                    Map<String, String> executionScope = DescriptorVisitor.namespaces(executions, scope);
                    for (Xml.Tag execution : children(executions, "execution", namespace, executionScope)) {
                        Map<String, String> configScope = DescriptorVisitor.namespaces(execution, executionScope);
                        List<Xml.Tag> executionPath = new ArrayList<>(path);
                        executionPath.add(executions); executionPath.add(execution);
                        for (Xml.Tag config : children(execution, "configuration", namespace, configScope))
                            add(config, resolved, executionPath, configScope, namespace, configurations);
                    }
                }
            }
        }
        for (Xml.Tag child : tag.getChildren()) collect(child, path, scope, namespace, configurations);
    }

    private static boolean pluginPath(List<Xml.Tag> path) {
        String joined = String.join("/", path.stream().map(DescriptorVisitor::local).toList());
        return Set.of("project/build/plugins/plugin", "project/build/pluginManagement/plugins/plugin",
                "project/profiles/profile/build/plugins/plugin", "project/profiles/profile/build/pluginManagement/plugins/plugin").contains(joined);
    }

    private static void add(Xml.Tag config, String identity, List<Xml.Tag> ancestors, Map<String, String> scope,
            String namespace, List<Configuration> configurations) {
        Map<String, String> configScope = DescriptorVisitor.namespaces(config, scope);
        boolean merge = ancestors.stream().anyMatch(MigrateMavenClientEnhancementOption::merge)
                || merge(config) || config.getChildren().stream().anyMatch(MigrateMavenClientEnhancementOption::merge);
        configurations.add(new Configuration(identity, children(config, OLD, namespace, configScope),
                children(config, NEW, namespace, configScope), merge));
    }

    private static boolean merge(Xml.Tag tag) {
        return tag.getAttributes().stream().anyMatch(a -> a.getKeyAsString().startsWith("combine."));
    }

    private static List<Xml.Tag> children(Xml.Tag parent, String local, String namespace, Map<String, String> inherited) {
        Map<String, String> scope = DescriptorVisitor.namespaces(parent, inherited);
        return parent.getChildren().stream().filter(t -> local.equals(DescriptorVisitor.local(t))
                && namespace.equals(DescriptorVisitor.namespace(t, DescriptorVisitor.namespaces(t, scope)))).toList();
    }

    private static String childValue(Xml.Tag tag, String name, String namespace, Map<String, String> inherited) {
        List<Xml.Tag> children = children(tag, name, namespace, inherited);
        return children.size() == 1 ? children.get(0).getValue().orElse("").trim() : "";
    }
}
