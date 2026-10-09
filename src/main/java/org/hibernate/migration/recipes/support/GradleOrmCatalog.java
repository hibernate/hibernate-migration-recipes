package org.hibernate.migration.recipes.support;

import org.openrewrite.*;
import org.openrewrite.toml.tree.Toml;
import java.util.*;

/// Structured local catalog declarations and stable accessor identities.
///
/// @author Steve Ebersole
public final class GradleOrmCatalog {
    public record Entry(Toml.KeyValue tree, String alias, String role, String group, String artifact, String version,
                 String reference, Map<String, Toml.KeyValue> fields) {}
    public final Toml.Document source;
    public final List<Entry> entries = new ArrayList<>();
    public final Map<String, String> versions = new HashMap<>();
    public final Map<String, List<String>> bundles = new HashMap<>();
    public final Set<String> names = new HashSet<>();
    public final Map<UUID, Integer> positions;

    public GradleOrmCatalog(Toml.Document source) {
        this.source = source;
        for (var value : source.getValues()) if (value instanceof Toml.Table table && table.getName() != null
                && table.getName().getName().equals("versions")) {
            for (Toml field : table.getValues()) if (field instanceof Toml.KeyValue kv) versions.put(key(kv), kv.getValue() instanceof Toml.Literal ? string(kv.getValue()) : "catalog constraint(...)");
        }
        Set<UUID> ids = new HashSet<>();
        for (var value : source.getValues()) if (value instanceof Toml.Table table && table.getName() != null) {
            String section = table.getName().getName();
            for (Toml field : table.getValues()) if (field instanceof Toml.KeyValue kv) {
                String alias = key(kv);
                if (section.equals("bundles") && kv.getValue() instanceof Toml.Array array) {
                    bundles.put(accessor(alias), array.getValues().stream().map(GradleOrmCatalog::string).toList()); continue;
                }
                if (!Set.of("libraries", "plugins").contains(section)) continue;
                Map<String, Toml.KeyValue> fields = new LinkedHashMap<>();
                if (kv.getValue() instanceof Toml.Table inline) for (Toml item : inline.getValues())
                    if (item instanceof Toml.KeyValue member) fields.put(key(member), member);
                String group = "", artifact = "", version = "", reference = "";
                if (kv.getValue() instanceof Toml.Literal) {
                    String[] parts = string(kv.getValue()).split(":", -1);
                    if (parts.length >= 2) { group = parts[0]; artifact = parts[1]; }
                    if (parts.length == 3) version = parts[2];
                }
                else {
                    String module = field(fields, "module");
                    String[] parts = module.split(":", -1);
                    group = parts.length == 2 ? parts[0] : field(fields, "group");
                    artifact = parts.length == 2 ? parts[1] : field(fields, "name");
                    if (section.equals("plugins")) { group = "plugin"; artifact = field(fields, "id"); }
                    version = field(fields, "version"); reference = field(fields, "version.ref");
                    // TOML also permits version = { ref = "..." }.
                    if (fields.get("version") != null && fields.get("version").getValue() instanceof Toml.Table v) {
                        for (Toml nested : v.getValues()) if (nested instanceof Toml.KeyValue ref && key(ref).equals("ref")) reference = string(ref.getValue());
                    }
                    if (!reference.isEmpty()) version = versions.getOrDefault(reference, "unresolved catalog version(...)");
                    else if (fields.get("version") != null && fields.get("version").getValue() instanceof Toml.Table) version = "catalog constraint(...)";
                }
                entries.add(new Entry(kv, accessor(alias), section.equals("plugins") ? "plugin" : "library", group, artifact, version, reference, fields));
                ids.add(kv.getId());
            }
        }
        positions = CoordinateSourceEdits.offsets(source, ids);
    }
    public Entry library(String name) { return entries.stream().filter(e -> e.role.equals("library") && e.alias.equals(name)).findFirst().orElse(null); }
    public Entry plugin(String name) { return entries.stream().filter(e -> e.role.equals("plugin") && e.alias.equals(name)).findFirst().orElse(null); }
    public static String accessor(String name) { return name.replace('-', '.').replace('_', '.'); }
    public static String key(Toml.KeyValue kv) {
        return kv.getKey() instanceof Toml.Identifier id ? id.getName() : string(kv.getKey());
    }
    public static String string(Toml tree) {
        return tree instanceof Toml.Literal literal && literal.getValue() instanceof String s ? s : "";
    }
    private static String field(Map<String, Toml.KeyValue> fields, String key) {
        return fields.containsKey(key) ? string(fields.get(key).getValue()) : "";
    }
    public static String quote(String old, String value) {
        String q = old.stripLeading().startsWith("'") ? "'" : "\"";
        return q + value + q;
    }
}
