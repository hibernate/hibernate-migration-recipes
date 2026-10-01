package org.hibernate.migration.recipes.orm80;

import org.openrewrite.xml.tree.Content;
import org.openrewrite.xml.tree.Xml;
import java.util.*;

/// Small namespace-preserving XML edits with local indentation and comment retention.
///
/// @author Steve Ebersole
final class CoordinateXmlEdits {
    private CoordinateXmlEdits() {}
    static Xml.Tag set(Xml.Tag tag, String name, String value) {
        var matching = OrmCoordinateSupport.children(tag, name);
        if (matching.size() == 1) {
            Xml.Tag old = matching.get(0);
            return tag.withContent(tag.getContent().stream().map(c -> c == old ? old.withValue(value) : c).toList());
        }
        return append(tag, Xml.Tag.build("<" + OrmCoordinateSupport.name(tag, name) + ">" + value + "</" + OrmCoordinateSupport.name(tag, name) + ">"));
    }
    static Xml.Tag remove(Xml.Tag tag, String name) {
        Set<UUID> ids = new HashSet<>();
        OrmCoordinateSupport.children(tag, name).forEach(t -> ids.add(t.getId()));
        if (ids.isEmpty()) return tag;
        List<Content> content = new ArrayList<>();
        for (Content c : tag.getContent()) {
            if (!ids.contains(c.getId())) content.add(c);
            else if (c instanceof Xml.Tag old && old.getContent() != null) {
                for (Content nested : old.getContent()) if (nested instanceof Xml.Comment comment)
                    content.add(comment.withPrefix(old.getPrefix() + comment.getPrefix()));
            }
        }
        return tag.withContent(content);
    }
    static Xml.Tag removeDeclarations(Xml.Tag parent, Set<UUID> ids) {
        if (parent.getContent() == null) return parent;
        List<Content> content = new ArrayList<>();
        for (Content child : parent.getContent()) {
            if (!ids.contains(child.getId())) content.add(child);
            else if (child instanceof Xml.Tag tag) retainComments(tag, tag.getPrefix(), content);
        }
        return content.size() == parent.getContent().size() && content.equals(parent.getContent()) ? parent : parent.withContent(content);
    }
    private static void retainComments(Xml.Tag tag, String prefix, List<Content> content) {
        if (tag.getContent() != null) for (Content child : tag.getContent()) {
            if (child instanceof Xml.Comment comment) content.add(comment.withPrefix(prefix + comment.getPrefix()));
            else if (child instanceof Xml.Tag nested) retainComments(nested, prefix, content);
        }
    }
    static String dependencyBehavior(Xml.Tag tag, boolean ivy, boolean ant) {
        Set<String> attributes = ant ? (ivy ? Set.of("org", "name", "rev", "revConstraint") : Set.of("groupId", "artifactId", "version")) : Set.of();
        List<String> behavior = new ArrayList<>();
        for (Xml.Attribute a : tag.getAttributes()) if (!attributes.contains(a.getKeyAsString())) {
            if (!ivy && a.getKeyAsString().equals("scope") && a.getValueAsString().equals("compile")) continue;
            if (!ivy && a.getKeyAsString().equals("type") && a.getValueAsString().equals("jar")) continue;
            behavior.add(a.getKeyAsString() + "=" + a.getValueAsString());
        }
        Set<String> identity = ant ? Set.of() : Set.of("groupId", "artifactId", "version");
        for (Xml.Tag child : tag.getChildren()) if (!identity.contains(OrmCoordinateSupport.local(child))) {
            String name = OrmCoordinateSupport.local(child), value = child.getValue().orElse("").trim();
            if (!ant && (name.equals("type") && value.equals("jar") || name.equals("scope") && value.equals("compile")
                    || name.equals("optional") && value.equals("false"))) continue;
            behavior.add(signature(child));
        }
        Collections.sort(behavior);
        return behavior.toString();
    }
    private static String signature(Xml.Tag tag) {
        List<String> children = new ArrayList<>();
        if (tag.getContent() != null) for (Content content : tag.getContent()) {
            if (content instanceof Xml.Tag child) children.add(signature(child));
            else if (content instanceof Xml.CharData data && !data.getText().isBlank()) children.add(data.getText().trim());
        }
        List<String> attributes = tag.getAttributes().stream().map(a -> a.getKeyAsString() + "=" + a.getValueAsString()).sorted().toList();
        return tag.getName() + attributes + ":" + tag.getValue().orElse("").trim() + children;
    }
    static Xml.Tag append(Xml.Tag parent, Xml.Tag child) {
        List<Content> content = new ArrayList<>(parent.getContent() == null ? List.of() : parent.getContent());
        String prefix = parent.getChildren().isEmpty() ? "" : parent.getChildren().get(0).getPrefix();
        if (prefix.isEmpty() && parent.getClosing() != null && parent.getClosing().getPrefix().contains("\n"))
            prefix = parent.getClosing().getPrefix() + "  ";
        content.add(indent(child, prefix, parent.getPrefix().contains("\r\n") ? "\r\n" : "\n"));
        return parent.withContent(content);
    }
    private static Xml.Tag indent(Xml.Tag tag, String prefix, String newline) {
        if (!prefix.contains("\n")) return tag.withPrefix(prefix);
        String indentation = prefix.substring(prefix.lastIndexOf('\n') + 1);
        List<Content> children = new ArrayList<>();
        if (tag.getContent() != null) for (Content child : tag.getContent()) {
            children.add(child instanceof Xml.Tag t ? indent(t, newline + indentation + "  ", newline) : child);
        }
        Xml.Tag result = tag.withPrefix(prefix);
        if (!children.isEmpty()) result = result.withContent(children);
        if (!tag.getChildren().isEmpty() && result.getClosing() != null)
            result = result.withClosing(result.getClosing().withPrefix(newline + indentation));
        return result;
    }
    static Xml.Tag attribute(Xml.Tag tag, String name, String value) {
        return tag.withAttributes(tag.getAttributes().stream().map(a -> name.equals(a.getKeyAsString())
                ? a.withValue(a.getValue().withValue(value)) : a).toList());
    }
}
