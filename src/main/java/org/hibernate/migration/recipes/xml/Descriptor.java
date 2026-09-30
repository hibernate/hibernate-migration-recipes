package org.hibernate.migration.recipes.xml;

import java.util.Set;

/// Internal descriptor identities shared by XML recipes; filenames are immaterial.
///
/// @author Steve Ebersole
public enum Descriptor {
    PERSISTENCE("persistence", "https://jakarta.ee/xml/ns/persistence", "4.0", Set.of("3.0", "3.1", "3.2", "4.0")),
    ORM("entity-mappings", "https://jakarta.ee/xml/ns/persistence/orm", "4.0", Set.of("3.0", "3.1", "3.2", "4.0")),
    MAPPING("entity-mappings", "http://www.hibernate.org/xsd/orm/mapping", "8.0", Set.of("7.0", "8.0"));

    public final String root;
    public final String namespace;
    public final String target;
    public final Set<String> versions;

    Descriptor(String root, String namespace, String target, Set<String> versions) {
        this.root = root;
        this.namespace = namespace;
        this.target = target;
        this.versions = versions;
    }

    public String schema(String version) {
        return this == MAPPING ? "https://www.hibernate.org/xsd/orm/mapping/mapping-" + version + ".xsd"
                : namespace + "/" + (this == ORM ? "orm" : "persistence") + "_" + version.replace('.', '_') + ".xsd";
    }
}
