/* SPDX-License-Identifier: Apache-2.0 */
package org.hibernate.migration.recipes.jpa4;

import org.hibernate.migration.recipes.table.SkippedMigrations;
import org.openrewrite.*;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.xml.XmlParser;
import org.openrewrite.xml.tree.Xml;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;

/// Runs XML conversions, verifies idempotence, and validates against published schemas.
///
/// @author Steve Ebersole
public final class XmlRecipeSupport {
    private XmlRecipeSupport() {}
    public record Outcome(String text, List<SkippedMigrations.Row> rows) {}

    public static Recipe composite(String name) { return ApiValidation.composite("org.hibernate.migration.recipes." + name); }

    public static Outcome run(Recipe recipe, String input) {
        List<Throwable> errors = new ArrayList<>();
        var ctx = new InMemoryExecutionContext(errors::add);
        SourceFile source = XmlParser.builder().build().parse(ctx, input).findFirst().orElseThrow().withSourcePath(Path.of("config/custom.xml"));
        assertInstanceOf(Xml.Document.class, source);
        var run = recipe.run(new InMemoryLargeSourceSet(List.of(source)), ctx);
        var changes = run.getChangeset().getAllResults();
        String output = changes.isEmpty() ? input : changes.getFirst().getAfter().printAll();
        SourceFile reparsed = XmlParser.builder().build().parse(ctx, output).findFirst().orElseThrow();
        assertInstanceOf(Xml.Document.class, reparsed);
        assertTrue(recipe.run(new InMemoryLargeSourceSet(List.of(reparsed)), new InMemoryExecutionContext(errors::add)).getChangeset().getAllResults().isEmpty(), "Second run changed XML");
        assertTrue(errors.isEmpty(), errors.toString());
        return new Outcome(output, run.getDataTableRows(SkippedMigrations.class));
    }

    public static byte[] schema(String resource) throws IOException {
        try (ZipFile jar = new ZipFile(ApiValidation.environments().value(ApiValidation.environments().primary().target() + ".ormJar"))) {
            var entry = jar.getEntry(resource);
            assertNotNull(entry, resource);
            try (InputStream stream = jar.getInputStream(entry)) { return stream.readAllBytes(); }
        }
    }

    public static void validate(String xml, String resource) throws Exception {
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var validator = factory.newSchema(new StreamSource(new ByteArrayInputStream(schema(resource)))).newValidator();
        validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        validator.validate(new StreamSource(new StringReader(xml)));
    }
}
