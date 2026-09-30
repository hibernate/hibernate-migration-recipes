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

/// Upgrades Hibernate mapping descriptors from 7.0 to 8.0, including table and column comments, ignored many-to-many orphan removal, and canonical schema references.
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
    private final transient SkippedMigrations skipped = new SkippedMigrations(this);

    @Override public @NonNull String getDisplayName() { return "Migrate Hibernate mapping XML to 8.0"; }
    @Override public @NonNull String getDescription() {
        return "Upgrades Hibernate mapping descriptors from 7.0 to 8.0, including table and column comments and canonical schema references. "
                + "Removes orphan-removal from many-to-many elements: Hibernate never processed this attribute, so removing it preserves existing behavior.";
    }
    @Override public @NonNull TreeVisitor<?, ExecutionContext> getVisitor() {
        return new DescriptorVisitor(this, skipped, Descriptor.MAPPING) {
            @Override public Xml.@NonNull Tag visitTag(Xml.@NonNull Tag tag, @NonNull ExecutionContext ctx) {
                String type = schemaType();
                if (type == null) return tag;
                if ("many-to-many".equals(type)) {
                    Xml.Tag result = super.visitTag(tag, ctx);
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
                            && descriptor.namespace.equals(namespace(child, namespaces(child, scope)))) insertion = i + 1;
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

            private String schemaType() {
                List<Xml.Tag> path = new ArrayList<>();
                for (Cursor c = getCursor(); c != null; c = c.getParent()) if (c.getValue() instanceof Xml.Tag t) path.add(t);
                Collections.reverse(path);
                String type = "entity-mappings";
                Map<String, String> scope = Map.of();
                for (int i = 0; i < path.size(); i++) {
                    Xml.Tag t = path.get(i);
                    scope = namespaces(t, scope);
                    if (!descriptor.namespace.equals(namespace(t, scope))) return null;
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
