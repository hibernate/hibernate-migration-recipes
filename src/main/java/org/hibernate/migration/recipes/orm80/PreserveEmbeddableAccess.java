package org.hibernate.migration.recipes.orm80;

import java.util.*;

import org.hibernate.migration.recipes.support.SourcePositions;
import org.hibernate.migration.recipes.table.SkippedMigrations;
import org.jspecify.annotations.NonNull;
import org.openrewrite.*;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.tree.J;
import org.openrewrite.xml.tree.Xml;

/// Preserves unambiguous legacy embeddable access across all supplied mapping occurrences.
/// Analysis is completed before editing; external mappings must be supplied for shared-type safety.
///
/// @author Steve Ebersole
public class PreserveEmbeddableAccess extends ScanningRecipe<EmbeddableAccessPlanner> {
    private final transient SkippedMigrations skipped = new SkippedMigrations(this);

    @Override public @NonNull String getDisplayName() { return "Preserve embeddable access"; }
    @Override public @NonNull String getDescription() {
        return "Adds explicit access to embeddables whose unambiguous legacy access changes in ORM 8, and reports mappings requiring review.";
    }
    @Override public EmbeddableAccessPlanner getInitialValue(ExecutionContext ctx) { return new EmbeddableAccessPlanner(); }

    @Override public TreeVisitor<?, ExecutionContext> getScanner(EmbeddableAccessPlanner analysis) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override public Tree preVisit(Tree tree, ExecutionContext ctx) {
                if (tree instanceof J.CompilationUnit cu) analysis.sources.add(cu);
                else if (tree instanceof Xml.Document document) analysis.xml.add(document);
                else if (tree instanceof SourceFile source && (source.getSourcePath().toString().endsWith("orm.xml")
                        || source.getSourcePath().toString().endsWith("persistence.xml") || source.getSourcePath().toString().endsWith(".hbm.xml"))) analysis.globalXml = true;
                stopAfterPreVisit();
                return tree;
            }
        };
    }

    @Override public TreeVisitor<?, ExecutionContext> getVisitor(EmbeddableAccessPlanner analysis) {
        analysis.plan();
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                SourcePositions positions = null;
                for (var diagnostic : analysis.diagnostics.values()) {
                    if (!diagnostic.member().source().getId().equals(cu.getId())) continue;
                    String key = getName() + ":embeddable-access:" + diagnostic.key();
                    Set<String> reported = getCursor().getRoot().computeMessageIfAbsent(key, k -> new HashSet<>());
                    if (!reported.add(diagnostic.code())) continue;
                    if (positions == null) positions = SourcePositions.capture(cu);
                    String[] location = positions.location(diagnostic.member().tree().getId()).split(":");
                    skipped.insertRow(ctx, new SkippedMigrations.Row(getName(), cu.getSourcePath().toString().replace('\\', '/'),
                            Integer.parseInt(location[0]), Integer.parseInt(location[1]), diagnostic.subject(),
                            "EMBEDDABLE_ACCESS_" + diagnostic.code(), diagnostic.message()));
                }
                return super.visitCompilationUnit(cu, ctx);
            }

            @Override public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration cd, ExecutionContext ctx) {
                var result = super.visitClassDeclaration(cd, ctx);
                var access = analysis.edits.get(cd.getId());
                if (access == null || EmbeddableAccessPlanner.annotation(cd.getAllAnnotations(), "Access") != null) return result;
                // Qualified references avoid collisions with application Access/AccessType declarations.
                // The tiny parser stubs keep persistence APIs off the recipe runtime classpath.
                return JavaTemplate.builder("@jakarta.persistence.Access(jakarta.persistence.AccessType." + access + ")")
                        .javaParser(JavaParser.fromJavaVersion().dependsOn(
                                "package jakarta.persistence; public enum AccessType { FIELD, PROPERTY }",
                                "package jakarta.persistence; public @interface Access { AccessType value(); }"))
                        .build().apply(updateCursor(result), result.getCoordinates().addAnnotation(Comparator.comparing(J.Annotation::getSimpleName)));
            }
        };
    }
}
