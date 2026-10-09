package org.hibernate.migration.recipes.orm80;

import org.hibernate.migration.recipes.table.SkippedMigrations;
import org.hibernate.migration.recipes.xml.DescriptorVisitor;
import org.jspecify.annotations.NonNull;
import org.openrewrite.*;
import org.openrewrite.xml.XmlIsoVisitor;
import org.openrewrite.xml.tree.Xml;

import java.util.*;

import static org.hibernate.migration.recipes.support.EnhancementMigrationSupport.*;

/// Migrates Ant enhancement attributes identified by same-document task definitions.
///
/// @author Steve Ebersole
public class MigrateAntClientEnhancementOption extends Recipe {
    private final transient SkippedMigrations skipped = new SkippedMigrations(this);
    private static final String TASK = "org.hibernate.tool.enhance.EnhancementTask";

    @Override public @NonNull String getDisplayName() { return "Migrate Ant client enhancement option"; }
    @Override public @NonNull String getDescription() {
        return "Renames Hibernate Ant enhancement attributes using explicit task identities and preserves existing client values.";
    }
    @Override public @NonNull TreeVisitor<?, ExecutionContext> getVisitor() {
        return new XmlIsoVisitor<ExecutionContext>() {
            @Override public Xml.@NonNull Document visitDocument(Xml.@NonNull Document doc, @NonNull ExecutionContext ctx) {
                if (!"project".equals(doc.getRoot().getName()) || !unnamespaced(doc.getRoot(), Map.of("", ""))) return doc;
                List<Xml.Tag> invocations = new ArrayList<>();
                Set<UUID> wrapped = new HashSet<>();
                Set<UUID> unresolved = new HashSet<>();
                Map<String, List<Definition>> definitions = new HashMap<>();
                MigrateAntClientEnhancementOption.collect(doc.getRoot(), Map.of("", ""), false, false, definitions, invocations, wrapped, unresolved);
                Set<UUID> candidates = new HashSet<>();
                invocations.forEach(t -> attributes(t, OLD).forEach(a -> candidates.add(a.getId())));
                Map<UUID, Integer> positions = offsets(doc, candidates);
                Set<UUID> rename = new HashSet<>(), remove = new HashSet<>();
                for (Xml.Tag tag : invocations) {
                    List<Xml.Attribute> old = attributes(tag, OLD), fresh = attributes(tag, NEW);
                    List<Definition> defs = definitions.getOrDefault(tag.getName(), List.of());
                    if (!unresolved.contains(tag.getId()) && defs.size() == 1 && defs.get(0).valid && !TASK.equals(defs.get(0).classname)) continue;
                    String reason = null, message = null;
                    if (unresolved.contains(tag.getId()) || defs.isEmpty() || defs.stream().anyMatch(d -> !d.valid)) {
                        reason = IDENTITY; message = "The Ant task identity is unresolved or conditional; establish its task definition manually.";
                    }
                    else if (defs.size() > 1 || old.size() > 1 || fresh.size() > 1) {
                        reason = AMBIGUOUS; message = "Competing task definitions or duplicate enhancement options require manual resolution.";
                    }
                    else if (wrapped.contains(tag.getId())) {
                        reason = SYNTAX; message = "Ant macro and preset wrappers require manual migration.";
                    }
                    if (reason != null) {
                        for (Xml.Attribute a : old) report(MigrateAntClientEnhancementOption.this, skipped, doc,
                                positions, a.getId(), reason, message, ctx);
                    }
                    else if (fresh.isEmpty()) rename.add(old.get(0).getId());
                    else remove.add(old.get(0).getId());
                }
                if (rename.isEmpty() && remove.isEmpty()) return doc;
                return (Xml.Document) new XmlIsoVisitor<ExecutionContext>() {
                    @Override public Xml.@NonNull Tag visitTag(Xml.@NonNull Tag tag, @NonNull ExecutionContext context) {
                        Xml.Tag result = super.visitTag(tag, context);
                        return result.withAttributes(result.getAttributes().stream().filter(a -> !remove.contains(a.getId()))
                                .map(a -> rename.contains(a.getId()) ? a.withKey(a.getKey().withName(NEW)) : a).toList());
                    }
                }.visitNonNull(doc, ctx);
            }
        };
    }

    private record Definition(String classname, boolean valid) {}

    private static void collect(Xml.Tag tag, Map<String, String> inherited, boolean conditional, boolean wrapper,
            Map<String, List<Definition>> definitions, List<Xml.Tag> invocations, Set<UUID> wrapped, Set<UUID> unresolved) {
        Map<String, String> scope = DescriptorVisitor.namespaces(tag, inherited);
        if (!unnamespaced(tag, inherited)) {
            if (!attributes(tag, OLD).isEmpty()) {
                invocations.add(tag);
                unresolved.add(tag.getId());
            }
            for (Xml.Tag child : tag.getChildren()) collect(child, scope, conditional, wrapper, definitions, invocations, wrapped, unresolved);
            return;
        }
        conditional |= tag.getAttributes().stream().anyMatch(a -> Set.of("if", "unless").contains(a.getKeyAsString()));
        wrapper |= Set.of("macrodef", "presetdef").contains(tag.getName());
        if ("taskdef".equals(tag.getName())) {
            String name = value(tag, "name"), classname = value(tag, "classname");
            if (!name.isEmpty() && !name.contains("${")) definitions.computeIfAbsent(name, k -> new ArrayList<>())
                    .add(new Definition(classname, !conditional && !wrapper && !classname.isEmpty() && !classname.contains("${")));
        }
        else if (!attributes(tag, OLD).isEmpty()) {
            invocations.add(tag);
            if (wrapper) wrapped.add(tag.getId());
        }
        for (Xml.Tag child : tag.getChildren()) collect(child, scope, conditional, wrapper, definitions, invocations, wrapped, unresolved);
    }

    private static boolean unnamespaced(Xml.Tag tag, Map<String, String> inherited) {
        return "".equals(DescriptorVisitor.namespace(tag, DescriptorVisitor.namespaces(tag, inherited)));
    }

    private static List<Xml.Attribute> attributes(Xml.Tag tag, String name) {
        return tag.getAttributes().stream().filter(a -> name.equals(a.getKeyAsString())).toList();
    }

    private static String value(Xml.Tag tag, String name) {
        List<Xml.Attribute> matches = attributes(tag, name);
        return matches.size() == 1 ? matches.get(0).getValueAsString() : "";
    }
}
