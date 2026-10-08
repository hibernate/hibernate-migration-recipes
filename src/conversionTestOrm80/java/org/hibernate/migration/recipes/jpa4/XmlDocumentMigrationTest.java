package org.hibernate.migration.recipes.jpa4;

import org.hibernate.migration.recipes.orm80.MigrateMappingXml;
import org.hibernate.migration.recipes.xml.Descriptor;
import org.junit.jupiter.api.Test;
import org.openrewrite.*;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.hibernate.migration.recipes.table.SkippedMigrations;
import org.openrewrite.xml.XmlParser;
import org.openrewrite.xml.tree.Xml;

import java.nio.file.Path;
import java.util.*;
import static org.hibernate.migration.recipes.jpa4.XmlRecipeSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/// Verifies one-visitor document migrations and rollback without contaminating other documents.
///
/// @author Steve Ebersole
class XmlDocumentMigrationTest {
    @Test void oneVisitUpdatesBothContentAndMetadata() throws Exception {
        for (Descriptor family : List.of(Descriptor.PERSISTENCE, Descriptor.MAPPING)) {
            String input = document(family, false);
            Recipe recipe = recipe(family);
            var errors = new ArrayList<Throwable>();
            var context = new InMemoryExecutionContext(errors::add);
            var source = XmlParser.builder().build().parse(context, input).findFirst().orElseThrow();
            // Invoke the visitor exactly once, without a composite or another recipe cycle.
            var result = (Xml.Document) recipe.getVisitor().visit(source, context);
            assertNotNull(result);
            String output = result.printAll();
            assertTrue(output.contains("version='" + family.target + "'"));
            assertTrue(output.contains(family.schema(family.target)));
            assertTrue(output.contains(family == Descriptor.PERSISTENCE ? "<package-descriptor>com.acme</package-descriptor>" : "<comment>convert</comment>"));
            assertFalse(output.contains(family == Descriptor.PERSISTENCE ? "package-info" : " comment="));
            assertEquals(output, run(recipe, input).text());
            validate(output, family == Descriptor.PERSISTENCE ? "org/hibernate/jpa/persistence_4_0.xsd" : "org/hibernate/xsd/mapping/mapping-8.0.xsd");
            assertTrue(errors.isEmpty(), errors.toString());
        }
    }

    @Test void conflictsRollBackAllChangesAndDoNotBlockTheNextDocument() {
        for (Descriptor family : List.of(Descriptor.PERSISTENCE, Descriptor.MAPPING)) {
            String invalid = document(family, true);
            var recipe = recipe(family);
            for (Recipe selected : List.of(recipe, composite("orm80"))) {
                var outcome = run(selected, invalid);
                assertEquals(invalid, outcome.text());
                assertEquals(1, outcome.rows().size());
                assertEquals(recipe.getName(), outcome.rows().getFirst().getRecipe());
            }
            var errors = new ArrayList<Throwable>();
            var context = new InMemoryExecutionContext(errors::add);
            List<SourceFile> sources = new ArrayList<>();
            // The real scheduler supplies the cycle context required for diagnostic tables.
            // Put an eligible document between two conflicted ones to exercise state isolation.
            for (String input : List.of(invalid, document(family, false), invalid)) {
                sources.add(XmlParser.builder().build().parse(context, input).findFirst().orElseThrow()
                        .withSourcePath(Path.of(sources.size() + ".xml")));
            }
            var batch = recipe.run(new InMemoryLargeSourceSet(sources), context);
            var changes = batch.getChangeset().getAllResults();
            assertEquals(1, changes.size());
            assertEquals(Path.of("1.xml"), changes.getFirst().getAfter().getSourcePath());
            assertTrue(changes.getFirst().getAfter().printAll().contains("version='" + family.target + "'"));
            assertEquals(2, batch.getDataTableRows(SkippedMigrations.class).size());
            assertTrue(errors.isEmpty(), errors.toString());
        }
    }

    private static Recipe recipe(Descriptor family) {
        return family == Descriptor.PERSISTENCE ? new MigratePersistenceXml() : new MigrateMappingXml();
    }

    private static String document(Descriptor family, boolean conflict) {
        String version = family == Descriptor.PERSISTENCE ? "3.2" : "7.0";
        String body = family == Descriptor.PERSISTENCE
                ? "<persistence-unit name='one'><class>com.acme.package-info</class></persistence-unit>"
                    + (conflict ? "<persistence-unit name='two'><class>com/acme/package-info</class></persistence-unit>" : "")
                : "<entity class='demo.Book'><table comment='convert'/>"
                    + (conflict ? "<secondary-table name='other' comment='old'><comment>new</comment></secondary-table>" : "") + "</entity>";
        return "<" + family.root + " xmlns='" + family.namespace + "' xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance' version='" + version
                + "' xsi:schemaLocation='" + family.namespace + " "
                + (family == Descriptor.MAPPING ? family.schema(version).replace("https:", "http:") : family.schema(version))
                + "'>" + body + "</" + family.root + ">";
    }
}
