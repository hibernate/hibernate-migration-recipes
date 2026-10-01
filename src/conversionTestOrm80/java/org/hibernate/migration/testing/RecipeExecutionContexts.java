package org.hibernate.migration.testing;

import java.util.function.Consumer;

import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.maven.MavenExecutionContextView;
import org.openrewrite.maven.cache.InMemoryMavenPomCache;
import org.openrewrite.maven.cache.MavenPomCache;

/// Creates fresh recipe execution contexts with a JVM-local cache of published Maven POMs.
/// Only contexts using the default Maven resolution settings may share this cache.
/// Tests changing repositories, mirrors, or credentials, or exercising resolution failures,
/// must create their own [InMemoryExecutionContext] and retain its isolated POM cache.
/// Recipe state, diagnostics, and other execution messages remain local to each context.
///
/// @author Steve Ebersole
public final class RecipeExecutionContexts {

	private static final MavenPomCache POM_CACHE = new InMemoryMavenPomCache();

	private RecipeExecutionContexts() {
	}

	public static InMemoryExecutionContext standard(Consumer<Throwable> onError) {
		var context = new InMemoryExecutionContext(onError);
		MavenExecutionContextView.view(context).setPomCache(POM_CACHE);
		return context;
	}
}
