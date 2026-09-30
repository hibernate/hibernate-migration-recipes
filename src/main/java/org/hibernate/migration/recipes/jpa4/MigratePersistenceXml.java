package org.hibernate.migration.recipes.jpa4;

import org.hibernate.migration.recipes.table.SkippedMigrations;
import org.hibernate.migration.recipes.xml.*;
import org.jspecify.annotations.NonNull;
import org.openrewrite.*;
import org.openrewrite.marker.Markers;
import org.openrewrite.xml.tree.Content;
import org.openrewrite.xml.tree.Xml;

import javax.lang.model.SourceVersion;
import java.util.*;

/// Upgrades Jakarta Persistence 3.x descriptors to 4.0, including package descriptors and canonical schema references.
///
/// @author Jennifer Joby
/// @author Steve Ebersole
public class MigratePersistenceXml extends Recipe {
    private final transient SkippedMigrations skipped = new SkippedMigrations(this);

    @Override public @NonNull String getDisplayName() { return "Migrate persistence.xml to JPA 4.0"; }
    @Override public @NonNull String getDescription() { return "Upgrades Jakarta Persistence 3.x descriptors to 4.0, including package descriptors and canonical schema references."; }
    @Override public @NonNull TreeVisitor<?, ExecutionContext> getVisitor() {
        return new DescriptorVisitor(this, skipped, Descriptor.PERSISTENCE) {
            @Override public Xml.@NonNull Tag visitTag(Xml.@NonNull Tag tag, @NonNull ExecutionContext ctx) {
                // Only direct persistence-unit children of the document root are eligible.
                Cursor parent = getCursor().getParent();
                if (!"persistence-unit".equals(local(tag)) || parent == null || !(parent.getValue() instanceof Xml.Tag)
                        || parent.getParent() == null || !(parent.getParent().getValue() instanceof Xml.Document)) return super.visitTag(tag, ctx);
                Map<String, String> scope = scope();
                if (!descriptor.namespace.equals(namespace(tag, scope)) || tag.getContent() == null) return tag;
                Set<String> existing = new HashSet<>();
                for (Xml.Tag child : tag.getChildren()) {
                    if (matches(child, scope, "package-descriptor")) {
                        String value = XmlText.text(child);
                        if (value != null) existing.add(value.trim());
                    }
                }
                List<Content> kept = new ArrayList<>();
                List<Xml.Tag> converted = new ArrayList<>();
                boolean changed = false;
                for (Content content : tag.getContent()) {
                    if (!(content instanceof Xml.Tag child) || !matches(child, scope, "class")) {
                        kept.add(content);
                        continue;
                    }
                    String value = XmlText.text(child);
                    String token = value == null ? "" : value.trim();
                    if (!token.contains("package-info") && !child.print(new Cursor(getCursor(), child)).contains("package-info")) {
                        kept.add(content);
                        continue;
                    }
                    String suffix = ".package-info";
                    String packageName = token.endsWith(suffix) ? token.substring(0, token.length() - suffix.length()) : "";
                    if (!SourceVersion.isName(packageName) || child.getContent() == null
                            || child.getContent().isEmpty() || child.getContent().stream().anyMatch(c -> !(c instanceof Xml.CharData))) {
                        skip(child, ctx, "XML_PACKAGE_DESCRIPTOR_UNSUPPORTED", "Use a dot-separated package name ending in .package-info; the document is unchanged and requires manual resolution.");
                        kept.add(content);
                        continue;
                    }
                    changed = true;
                    if (existing.add(packageName)) {
                        Xml.CharData data = (Xml.CharData) child.getContent().get(0);
                        if (child.getContent().size() > 1) {
                            // Entity references are separate CharData nodes. Coalesce only the changed value.
                            int leading = value.indexOf(token);
                            String replacement = value.substring(0, leading) + packageName + value.substring(leading + token.length());
                            Xml.Tag target = child.withName(renamed(child, "package-descriptor"))
                                    .withContent(List.of(new Xml.CharData(Tree.randomId(), "", Markers.EMPTY, false, XmlText.escape(replacement), "")));
                            if (target.getClosing() != null) target = target.withClosing(target.getClosing().withPrefix(""));
                            converted.add(target);
                            continue;
                        }
                        // Retain both external CharData whitespace and whitespace inside CDATA/references.
                        String text = data.isCdata() ? data.getText() : XmlText.characters(data.getText());
                        int start = 0, end = text.length();
                        while (start < end && text.charAt(start) <= ' ') start++;
                        while (end > start && text.charAt(end - 1) <= ' ') end--;
                        String replacement = text.substring(0, start) + packageName + text.substring(end);
                        converted.add(child.withName(renamed(child, "package-descriptor"))
                                .withContent(List.of(data.withText(data.isCdata() ? replacement : XmlText.escape(replacement)))));
                    }
                }
                if (!changed) return tag;
                int insertion = 0;
                Set<String> preceding = Set.of("description", "provider", "jta-data-source", "non-jta-data-source", "mapping-file", "jar-file", "class", "package-descriptor");
                for (int i = 0; i < kept.size(); i++) {
                    if (kept.get(i) instanceof Xml.Tag child && preceding.contains(local(child))
                            && descriptor.namespace.equals(namespace(child, namespaces(child, scope)))) insertion = i + 1;
                }
                kept.addAll(insertion, converted);
                return tag.withContent(kept);
            }
        };
    }
}
