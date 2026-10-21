package org.hibernate.migration.recipes.orm80;

import org.hibernate.migration.recipes.table.SkippedMigrations;
import org.hibernate.migration.recipes.xml.*;
import org.jspecify.annotations.NonNull;
import org.openrewrite.*;
import org.openrewrite.xml.tree.Content;
import org.openrewrite.xml.tree.Xml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;

/// Upgrades Hibernate mapping descriptors from 7.0 to 8.0, including versioning exclusions, table and column comments, ignored many-to-many orphan removal, and HTTPS mapping namespaces and schema references.
///
/// @author Steve Ebersole
public class MigrateMappingXml extends Recipe {
    private static final Properties CONTEXTS = contexts();
    // Children which precede the common table extensions in mapping-8.0.xsd.
    private static final Map<String, Set<String>> PRECEDING = Map.of(
            "collection-table", Set.of("join-column", "foreign-key"),
            "secondary-table", Set.of("primary-key-join-column", "primary-key-foreign-key"),
            "table-generator", Set.of("description"),
            "column", Set.of(), "join-column", Set.of(), "join-table", Set.of(), "table", Set.of());
    private static final Set<String> LOCKABLE = Set.of("basic", "embedded", "element-collection", "many-to-one",
            "one-to-one", "many-to-many", "one-to-many", "hbm-any-mapping", "hbm-many-to-any");
    private final transient SkippedMigrations skipped = new SkippedMigrations(this);

    @Override public @NonNull String getDisplayName() { return "Migrate Hibernate mapping XML to 8.0"; }
    @Override public @NonNull String getDescription() {
        return "Upgrades Hibernate mapping descriptors from 7.0 to 8.0, including table and column comments and HTTPS mapping namespaces and schema references. "
                + "Converts optimistic-lock exclusions and named excluded-from-versioning entries to nested exclude-from-versioning elements. Removes orphan-removal from many-to-many elements: Hibernate never processed this attribute, so removing it preserves existing behavior.";
    }
    @Override public @NonNull TreeVisitor<?, ExecutionContext> getVisitor() {
        return new DescriptorVisitor(this, skipped, Descriptor.MAPPING) {
            @Override public Xml.@NonNull Tag visitTag(Xml.@NonNull Tag tag, @NonNull ExecutionContext ctx) {
                String type = schemaType();
                if (type == null) return tag;
                if ("attributes".equals(type) || "embeddable-attributes".equals(type)) {
                    return migrateExclusions(tag, super.visitTag(tag, ctx), ctx);
                }
                if (LOCKABLE.contains(type)) {
                    Xml.Tag result = migrateOptimisticLock(tag, super.visitTag(tag, ctx), ctx);
                    if (!"many-to-many".equals(type)) return result;
                    if (result.getAttributes().stream().noneMatch(a -> a.getKeyAsString().equals("orphan-removal"))) return result;
                    return result.withAttributes(result.getAttributes().stream()
                            .filter(a -> !a.getKeyAsString().equals("orphan-removal")).toList());
                }
                if (!PRECEDING.containsKey(type)) return super.visitTag(tag, ctx);
                List<Xml.Attribute> attributes = tag.getAttributes().stream().filter(a -> a.getKeyAsString().equals("comment")).toList();
                if (attributes.isEmpty()) return super.visitTag(tag, ctx);
                Xml.Attribute attribute = attributes.get(0);
                String value = XmlText.attribute(attribute.getValueAsString());
                Map<String, String> scope = scope();
                List<Xml.Tag> comments = tag.getChildren().stream().filter(t -> matches(t, scope, "comment")).toList();
                if (attributes.size() != 1 || value == null || comments.size() > 1
                        || (!comments.isEmpty() && !value.equals(XmlText.text(comments.get(0))))) {
                    skip(attribute, ctx, "XML_COMMENT_CONFLICT", "The comment cannot be migrated unambiguously; the document is unchanged and requires manual resolution.");
                    return tag;
                }
                Xml.Tag result = super.visitTag(tag, ctx);
                result = result.withAttributes(result.getAttributes().stream().filter(a -> !a.getId().equals(attribute.getId())).toList());
                if (!comments.isEmpty()) return result;
                List<Content> content = result.getContent() == null ? new ArrayList<>() : new ArrayList<>(result.getContent());
                int insertion = 0;
                for (int i = 0; i < content.size(); i++) {
                    if (content.get(i) instanceof Xml.Tag child && PRECEDING.get(type).contains(local(child))
                            && descriptor.acceptsNamespace(namespace(child, namespaces(child, scope)))) insertion = i + 1;
                }
                String prefix = result.getChildren().isEmpty() ? "" : result.getChildren().get(0).getPrefix();
                if (result.getChildren().isEmpty() && tag.getPrefix().contains("\n")) {
                    int newline = tag.getPrefix().lastIndexOf('\n');
                    prefix = (newline > 0 && tag.getPrefix().charAt(newline - 1) == '\r' ? "\r\n" : "\n")
                            + tag.getPrefix().substring(newline + 1) + "    ";
                }
                String name = renamed(tag, "comment");
                Xml.Tag comment = Xml.Tag.build("<" + name + ">" + XmlText.escape(value) + "</" + name + ">").withPrefix(prefix);
                content.add(insertion, comment);
                result = result.withContent(content);
                // withContent expands a self-closing tag; choose a closing prefix matching the new child layout.
                if (tag.getClosing() == null && result.getClosing() != null) {
                    String closing = prefix.contains("\n") ? prefix.substring(0, prefix.length() - 4) : "";
                    result = result.withClosing(result.getClosing().withPrefix(closing));
                }
                return result;
            }

            private Xml.Tag migrateOptimisticLock(Xml.Tag original, Xml.Tag result, ExecutionContext ctx) {
                List<Xml.Attribute> locks = original.getAttributes().stream()
                        .filter(a -> a.getKeyAsString().equals("optimistic-lock")).toList();
                if (locks.isEmpty()) return result;
                String value = XmlText.attribute(locks.get(0).getValueAsString());
                if (locks.size() != 1 || value == null || !Set.of("true", "false", "1", "0").contains(value.trim())) {
                    skip(locks.get(0), ctx, "XML_VERSIONING_EXCLUSION_UNRESOLVED", "Expected one literal XML boolean optimistic-lock attribute.");
                    return original;
                }
                List<Xml.Tag> existing = result.getChildren().stream().filter(t -> matches(t, scope(), "exclude-from-versioning")).toList();
                if (existing.size() > 1 || (!existing.isEmpty() && Set.of("true", "1").contains(value.trim()))) {
                    skip(locks.get(0), ctx, "XML_VERSIONING_EXCLUSION_CONFLICT", "Explicit versioning inclusion conflicts with nested exclusion, or exclusions are duplicated.");
                    return original;
                }
                // Explicit inclusion still has a supported representation and may override annotation defaults.
                if (Set.of("true", "1").contains(value.trim())) return result;
                result = result.withAttributes(result.getAttributes().stream().filter(a -> !a.getKeyAsString().equals("optimistic-lock")).toList());
                if (!existing.isEmpty()) return result;
                String name = renamed(original, "exclude-from-versioning");
                return insertExclusion(result, Xml.Tag.build("<" + name + "/>"));
            }

            private Xml.Tag insertExclusion(Xml.Tag target, Xml.Tag exclusion) {
                List<Content> children = target.getContent() == null ? new ArrayList<>() : new ArrayList<>(target.getContent());
                String prefix = target.getChildren().isEmpty() ? "" : target.getChildren().get(0).getPrefix();
                if (target.getChildren().isEmpty() && target.getPrefix().contains("\n")) {
                    int newline = target.getPrefix().lastIndexOf('\n');
                    prefix = (newline > 0 && target.getPrefix().charAt(newline - 1) == '\r' ? "\r\n" : "\n")
                            + target.getPrefix().substring(newline + 1) + "    ";
                }
                int insertion = children.size();
                for (int j = 0; j < children.size(); j++) {
                    if (children.get(j) instanceof Xml.Tag t && (matches(t, namespaces(target, scope()), "mutable")
                            || matches(t, namespaces(target, scope()), "on-delete"))) { insertion = j; break; }
                }
                children.add(insertion, exclusion.withPrefix(prefix));
                Xml.Tag result = target.withContent(children);
                if (target.getClosing() == null && result.getClosing() != null && prefix.contains("\n")) {
                    result = result.withClosing(result.getClosing().withPrefix(prefix.substring(0, prefix.length() - 4)));
                }
                return result;
            }

            private Xml.Tag migrateExclusions(Xml.Tag original, Xml.Tag result, ExecutionContext ctx) {
                Map<String, String> scope = scope();
                Set<String> supported = Set.of("basic", "embedded", "element-collection", "many-to-one",
                        "one-to-one", "many-to-many", "one-to-many", "any", "many-to-any");
                for (Xml.Tag exclusion : original.getChildren()) {
                    if (!matches(exclusion, scope, "excluded-from-versioning")) continue;
                    List<Xml.Attribute> names = exclusion.getAttributes().stream()
                            .filter(a -> a.getKeyAsString().equals("name")).toList();
                    String name = names.size() == 1 ? XmlText.attribute(names.get(0).getValueAsString()) : null;
                    List<Xml.Tag> targets = name == null ? List.of() : original.getChildren().stream()
                            .filter(t -> !local(t).equals("excluded-from-versioning"))
                            .filter(t -> descriptor.acceptsNamespace(namespace(t, namespaces(t, scope))))
                            .filter(t -> t.getAttributes().stream().anyMatch(a -> a.getKeyAsString().equals("name")
                                    && name.equals(XmlText.attribute(a.getValueAsString())))).toList();
                    if (name == null || name.isBlank() || targets.size() != 1 || !supported.contains(local(targets.get(0)))
                            || exclusion.getAttributes().stream().anyMatch(a -> !a.getKeyAsString().equals("name")
                                    && !a.getKeyAsString().equals("xmlns") && !a.getKeyAsString().startsWith("xmlns:"))
                            || !exclusion.getChildren().isEmpty() || !exclusion.getValue().orElse("").isBlank()) {
                        skip(exclusion, ctx, "XML_VERSIONING_EXCLUSION_UNRESOLVED",
                                "The named versioning exclusion must identify exactly one supported attribute mapping and contain no extra data.");
                        continue;
                    }
                    Xml.Tag originalTarget = targets.get(0);
                    Xml.Tag target = result.getChildren().stream().filter(t -> t.getId().equals(originalTarget.getId())).findFirst().orElseThrow();
                    List<Xml.Tag> existing = target.getChildren().stream()
                            .filter(t -> matches(t, namespaces(target, scope), "exclude-from-versioning")).toList();
                    boolean inclusion = target.getAttributes().stream().anyMatch(a -> a.getKeyAsString().equals("optimistic-lock")
                            && Set.of("true", "1").contains(a.getValueAsString().trim()));
                    if (inclusion || existing.size() > 1) {
                        skip(exclusion, ctx, "XML_VERSIONING_EXCLUSION_CONFLICT",
                                "The versioning exclusion conflicts with explicit inclusion or duplicate nested exclusions.");
                        continue;
                    }
                    // Retain the legacy element's comments and namespace declarations when relocating it.
                    List<Content> content = new ArrayList<>(result.getContent());
                    for (int i = 0; i < content.size(); i++) {
                        if (!(content.get(i) instanceof Xml.Tag child) || !child.getId().equals(target.getId())) continue;
                        List<Content> children = child.getContent() == null ? new ArrayList<>() : new ArrayList<>(child.getContent());
                        if (existing.isEmpty()) {
                            Xml.Tag nested = exclusion.withName(renamed(exclusion, "exclude-from-versioning"))
                                    .withAttributes(exclusion.getAttributes().stream().filter(a -> !a.getKeyAsString().equals("name")).toList());
                            // Its prefix may be rebound on the target; bind it explicitly at the new location.
                            String binding = nested.getName().contains(":") ? "xmlns:" + nested.getName().split(":")[0] : "xmlns";
                            if (!Objects.equals(namespace(nested, namespaces(nested, namespaces(child, scope))), namespace(exclusion, namespaces(exclusion, scope)))) {
                                List<Xml.Attribute> bindings = new ArrayList<>(nested.getAttributes());
                                bindings.add(Xml.Tag.build("<x " + binding + "=\"" + descriptor.namespace + "\"/>").getAttributes().get(0));
                                nested = nested.withAttributes(bindings);
                            }
                            child = insertExclusion(child, nested);
                        }
                        else if (exclusion.getContent() != null) {
                            // An already migrated exclusion is redundant, but its comments still belong in the output.
                            Xml.Tag nested = existing.get(0);
                            List<Content> merged = nested.getContent() == null ? new ArrayList<>() : new ArrayList<>(nested.getContent());
                            merged.addAll(exclusion.getContent());
                            for (int j = 0; j < children.size(); j++) {
                                if (children.get(j).getId().equals(nested.getId())) children.set(j, nested.withContent(merged));
                            }
                        }
                        content.set(i, existing.isEmpty() ? child : child.withContent(children));
                        break;
                    }
                    content.removeIf(c -> c.getId().equals(exclusion.getId()));
                    result = result.withContent(content);
                }
                return result;
            }

            private String schemaType() {
                List<Xml.Tag> path = new ArrayList<>();
                for (Cursor c = getCursor(); c != null; c = c.getParent()) if (c.getValue() instanceof Xml.Tag t) path.add(t);
                Collections.reverse(path);
                String type = "entity-mappings";
                Map<String, String> scope = Map.of();
                for (int i = 0; i < path.size(); i++) {
                    Xml.Tag t = path.get(i);
                    scope = namespaces(t, scope);
                    if (!descriptor.acceptsNamespace(namespace(t, scope))) return null;
                    if (i != 0) type = CONTEXTS.getProperty(type + "/" + local(t));
                    if (type == null) return null;
                }
                return type;
            }
        };
    }

    private static Properties contexts() {
        Properties result = new Properties();
        try (var stream = MigrateMappingXml.class.getResourceAsStream("mapping-comment-contexts.properties")) {
            if (stream == null) throw new IllegalStateException("Missing mapping comment contexts");
            result.load(stream);
        }
        catch (IOException e) { throw new UncheckedIOException(e); }
        return result;
    }
}
