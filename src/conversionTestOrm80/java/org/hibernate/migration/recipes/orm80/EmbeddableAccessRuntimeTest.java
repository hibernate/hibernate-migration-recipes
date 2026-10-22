package org.hibernate.migration.recipes.orm80;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;
import org.hibernate.migration.testing.ApiValidation;
import org.hibernate.migration.testing.MigrationSources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Runs provider versions in separate JVMs to compare metadata and persisted member state.
/// A corrected local provider classpath can verify fixes before their release in Beta4.
/// @author Steve Ebersole
class EmbeddableAccessRuntimeTest {
    @TempDir Path directory;
    private static final String FIXTURE = "fixture/embeddableaccess/RuntimeAccess.java";

    @Test void legacyMetadataAndRoundTrips() throws Exception {
        String cp = classpath("orm74");
        Path classes = compile(MigrationSources.configured().files(FIXTURE), cp, "legacy");
        assertTrue(run(classes, cp, "preserved").contains("ACCESS_PRESERVED"));
    }

    @Test void correctedTargetPreservesLegacyAndUnmodifiedTargetDemonstratesDifference() throws Exception {
        String cp = System.getProperty("embeddableAccess.targetClasspath", classpath("orm80"));
        if (System.getProperty("embeddableAccess.targetClasspathFile") != null) cp = Files.readString(Path.of(System.getProperty("embeddableAccess.targetClasspathFile"))).trim();
        cp += File.pathSeparator + h2();
        var sources = MigrationSources.configured().files(FIXTURE);
        Path original = compile(sources, cp, "original");
        var probe = execute(original, cp, "probe");
        assertTrue(probe.exit == 0 || probe.exit == 77, probe.output);
        assumeTrue(probe.exit == 0, "Configured target predates HHH-13537/HHH-21017; use Beta4 or -PembeddableAccessTargetClasspath=<corrected runtime classpath>");
        assertTrue(run(original, cp, "baseline").contains("BASELINE_DIFFERENCE_VERIFIED"));
        var converted = ApiValidation.run(new PreserveEmbeddableAccess(), sources, "orm74");
        assertTrue(converted.skipped().isEmpty(), converted.skipped().stream().map(r -> r.getReasonCode()+":"+r.getSubject()).toList().toString());
        Path migrated = compile(converted.files(), cp, "migrated");
        assertTrue(run(migrated, cp, "preserved").contains("ACCESS_PRESERVED"));
    }

    private String classpath(String profile) {
        String runtime = ApiValidation.environments().value(profile + ".runtime");
        return runtime + File.pathSeparator + h2();
    }
    private String h2() {
        return Arrays.stream(System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator)))
                .filter(p -> Path.of(p).getFileName().toString().startsWith("h2-")).findFirst().orElseThrow();
    }
    private Path compile(Map<String,String> sources, String cp, String name) throws Exception {
        Path root = Files.createDirectories(directory.resolve(name));
        List<String> args = new ArrayList<>(List.of("-proc:none", "-classpath", cp, "-d", root.toString()));
        for (var entry : sources.entrySet()) {
            Path file = root.resolve(entry.getKey()); Files.createDirectories(file.getParent()); Files.writeString(file,entry.getValue()); args.add(file.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new)));
        return root;
    }
    private String run(Path classes, String cp, String mode) throws Exception {
        var result = execute(classes, cp, mode); assertEquals(0,result.exit,result.output); return result.output;
    }
    private Execution execute(Path classes, String cp, String mode) throws Exception {
        Path log = directory.resolve(UUID.randomUUID()+".log");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-cp", classes+File.pathSeparator+cp, "fixture.embeddableaccess.RuntimeAccess",mode)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(50,TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Provider runtime timed out: "+Files.readString(log)); }
        return new Execution(process.exitValue(),Files.readString(log));
    }
    private record Execution(int exit, String output) {}
}
