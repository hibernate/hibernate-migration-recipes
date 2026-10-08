package org.hibernate.migration.recipes.xml;

import org.hibernate.migration.recipes.table.SkippedMigrations;
import org.jspecify.annotations.NonNull;
import org.openrewrite.*;
import org.openrewrite.xml.XmlIsoVisitor;
import org.openrewrite.xml.internal.XmlPrinter;
import org.openrewrite.xml.tree.Xml;

import java.util.*;
import java.util.regex.Pattern;

/// Internal eligibility, namespace resolution, and original-source diagnostics for XML recipes.
/// No schema is fetched or validated at recipe execution time.
///
/// @author Steve Ebersole
public class DescriptorVisitor extends XmlIsoVisitor<ExecutionContext> {
    private static final String XSI = "http://www.w3.org/2001/XMLSchema-instance";
    protected final Descriptor descriptor;
    private final Recipe recipe;
    private final SkippedMigrations skipped;
    private boolean contentSkipped;
    private Xml.Document input;
    private Positions positions;

    public DescriptorVisitor(Recipe recipe, SkippedMigrations skipped, Descriptor descriptor) {
        this.recipe = recipe;
        this.skipped = skipped;
        this.descriptor = descriptor;
    }

    @Override
    public Xml.@NonNull Document visitDocument(Xml.@NonNull Document doc, @NonNull ExecutionContext ctx) {
        contentSkipped = false;
        Xml.Tag root = doc.getRoot();
        if (!descriptor.root.equals(local(root))) return doc;
        Map<String, String> namespaces = namespaces(root, Map.of("xml", "http://www.w3.org/XML/1998/namespace"));
        boolean matching = descriptor.namespace.equals(namespace(root, namespaces));
        if (!matching && descriptor != Descriptor.PERSISTENCE) return doc;
        input = doc;
        // Keep locations from the first XML recipe, including across composite recipes and cycles.
        positions = getCursor().getRoot().computeMessageIfAbsent("hibernate.xml.positions." + doc.getId(), k -> capture(doc));
        if (!matching) return reject(doc, ctx, "XML_NAMESPACE_UNSUPPORTED", "The persistence root must declare the Jakarta Persistence namespace.");
        Xml.Attribute version = null, schema = null;
        Set<String> expanded = new HashSet<>();
        String problem = null;
        for (Xml.Attribute a : root.getAttributes()) {
            String key = a.getKeyAsString();
            if (key.equals("xmlns") || key.startsWith("xmlns:")) continue;
            String[] parts = key.split(":", -1);
            String local = parts[parts.length - 1];
            String uri = parts.length == 1 ? "" : namespaces.get(parts[0]);
            if (parts.length > 2 || uri == null || !expanded.add("{" + uri + "}" + local)) {
                problem = "Duplicate attributes or unresolved namespace prefixes.";
            }
            if (key.equals("version")) version = a;
            if ("schemaLocation".equals(local)) {
                if (XSI.equals(uri)) schema = a;
                else if (uri == null || uri.isEmpty()) problem = "The schemaLocation attribute has no schema-instance namespace binding.";
            }
            if ("noNamespaceSchemaLocation".equals(local) && XSI.equals(uri)) problem = "A namespaced descriptor cannot use noNamespaceSchemaLocation.";
        }
        String v = version == null ? "" : version.getValueAsString();
        if (!descriptor.versions.contains(v)) return reject(doc, ctx, "XML_VERSION_UNSUPPORTED", "Unsupported or missing " + descriptor.root + " version: " + v);
        if (problem != null) return reject(doc, ctx, "XML_SCHEMA_CONFLICT", problem);
        String replacement = null;
        if (schema != null) {
            String value = schema.getValueAsString();
            var tokens = Pattern.compile("\\S+").matcher(value);
            List<String> words = new ArrayList<>();
            List<Integer> starts = new ArrayList<>(), ends = new ArrayList<>();
            while (tokens.find()) { words.add(tokens.group()); starts.add(tokens.start()); ends.add(tokens.end()); }
            if (words.size() % 2 != 0) return reject(doc, ctx, "XML_SCHEMA_CONFLICT", "schemaLocation must contain namespace/location pairs.");
            boolean found = false;
            for (int i = 0; i < words.size(); i += 2) {
                if (!descriptor.namespace.equals(words.get(i))) continue;
                if (found) return reject(doc, ctx, "XML_SCHEMA_CONFLICT", "Duplicate " + descriptor.root + " schema pairs.");
                found = true;
                String expected = descriptor.schema(v);
                String target = descriptor.schema(descriptor.target);
                if (descriptor == Descriptor.MAPPING && words.get(i + 1).startsWith("http:")) {
                    expected = expected.replace("https:", "http:");
                }
                if (!expected.equals(words.get(i + 1))) return reject(doc, ctx, "XML_SCHEMA_LOCATION_UNSUPPORTED", "The schema location does not match the declared version's canonical schema.");
                replacement = value.substring(0, starts.get(i + 1)) + target + value.substring(ends.get(i + 1));
            }
        }
        Xml.Document migrated = super.visitDocument(doc, ctx);
        // Keep metadata and content together: a skipped candidate cancels this document's edits.
        if (contentSkipped) return doc;
        if (descriptor.target.equals(v) && (replacement == null || replacement.equals(schema.getValueAsString()))) return migrated;
        List<Xml.Attribute> attributes = new ArrayList<>();
        for (Xml.Attribute a : root.getAttributes()) {
            if (a == version) attributes.add(a.withValue(a.getValue().withValue(descriptor.target)));
            else if (a == schema && replacement != null) attributes.add(a.withValue(a.getValue().withValue(replacement)));
            else attributes.add(a);
        }
        return migrated.withRoot(migrated.getRoot().withAttributes(attributes));
    }

    private Xml.Document reject(Xml.Document doc, ExecutionContext ctx, String reason, String message) {
        skip(doc.getRoot(), ctx, reason, message);
        return doc;
    }

    protected void skip(Xml candidate, ExecutionContext ctx, String reason, String message) {
        contentSkipped = true;
        Set<String> reported = getCursor().getRoot().computeMessageIfAbsent("hibernate.skipped", k -> new HashSet<String>());
        String key = recipe.getName() + ":" + input.getId() + ":" + candidate.getId();
        if (!reported.add(key)) return;
        Integer offset = positions.offsets.get(candidate.getId());
        if (offset == null) throw new IllegalStateException("Missing original XML position");
        int line = 1, column = 1;
        for (int i = 0; i < offset; i++) {
            if (positions.source.charAt(i) == '\n') { line++; column = 1; }
            else column++;
        }
        skipped.insertRow(ctx, new SkippedMigrations.Row(recipe.getName(), input.getSourcePath().toString().replace('\\', '/'),
                line, column, candidate instanceof Xml.Tag t ? local(t) : ((Xml.Attribute) candidate).getKeyAsString(), reason, message));
    }

    private static Positions capture(Xml.Document doc) {
        Map<UUID, Integer> offsets = new HashMap<>();
        PrintOutputCapture<Integer> out = new PrintOutputCapture<>(0);
        new XmlPrinter<Integer>() {
            @Override public Xml visitTag(Xml.Tag tag, PrintOutputCapture<Integer> p) {
                offsets.put(tag.getId(), p.getOut().length() + tag.getPrefix().length());
                return super.visitTag(tag, p);
            }
            @Override public Xml visitAttribute(Xml.Attribute attribute, PrintOutputCapture<Integer> p) {
                offsets.put(attribute.getId(), p.getOut().length() + attribute.getPrefix().length());
                return super.visitAttribute(attribute, p);
            }
        }.visit(doc, out);
        return new Positions(out.getOut(), offsets);
    }

    private record Positions(String source, Map<UUID, Integer> offsets) {}

    public static String local(Xml.Tag tag) {
        String name = tag.getName();
        return name.substring(name.lastIndexOf(':') + 1);
    }

    public static String renamed(Xml.Tag tag, String local) {
        int colon = tag.getName().indexOf(':');
        return colon < 0 ? local : tag.getName().substring(0, colon + 1) + local;
    }

    public static Map<String, String> namespaces(Xml.Tag tag, Map<String, String> inherited) {
        Map<String, String> result = new HashMap<>(inherited);
        for (Xml.Attribute a : tag.getAttributes()) {
            if (a.getKeyAsString().equals("xmlns")) result.put("", a.getValueAsString());
            else if (a.getKeyAsString().startsWith("xmlns:")) result.put(a.getKeyAsString().substring(6), a.getValueAsString());
        }
        return result;
    }

    public static String namespace(Xml.Tag tag, Map<String, String> scope) {
        String[] parts = tag.getName().split(":", -1);
        return parts.length > 2 ? null : scope.get(parts.length == 1 ? "" : parts[0]);
    }

    protected Map<String, String> scope() {
        List<Xml.Tag> ancestors = new ArrayList<>();
        for (Cursor c = getCursor(); c != null; c = c.getParent()) {
            if (c.getValue() instanceof Xml.Tag t) ancestors.add(t);
        }
        Collections.reverse(ancestors);
        Map<String, String> scope = Map.of("xml", "http://www.w3.org/XML/1998/namespace");
        for (Xml.Tag t : ancestors) scope = namespaces(t, scope);
        return scope;
    }

    protected boolean matches(Xml.Tag tag, Map<String, String> parentScope, String name) {
        return name.equals(local(tag)) && descriptor.namespace.equals(namespace(tag, namespaces(tag, parentScope)));
    }
}
