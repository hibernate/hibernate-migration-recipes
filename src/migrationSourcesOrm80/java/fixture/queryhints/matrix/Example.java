package fixture.queryhints.matrix;

import org.hibernate.jpa.QueryHints;

/// Query-hint migration input.
///
/// @author Steve Ebersole
class Example {
	String[] hints = {
		QueryHints.HINT_COMMENT,
		QueryHints.HINT_FETCH_SIZE,
		QueryHints.HINT_CACHEABLE,
		QueryHints.HINT_CACHE_REGION,
		QueryHints.HINT_CACHE_MODE,
		QueryHints.HINT_READONLY,
		QueryHints.HINT_FLUSH_MODE,
		QueryHints.HINT_NATIVE_LOCKMODE,
		QueryHints.HINT_LIMIT_IN_MEMORY,
		QueryHints.HINT_FOLLOW_ON_STRATEGY,
		QueryHints.HINT_FOLLOW_ON_LOCKING,
		QueryHints.HINT_NATIVE_SPACES,
		QueryHints.HINT_TIMEOUT,
		QueryHints.JAKARTA_SPEC_HINT_TIMEOUT,
		QueryHints.JAKARTA_HINT_FETCH_GRAPH,
		QueryHints.JAKARTA_HINT_FETCHGRAPH,
		QueryHints.JAKARTA_HINT_LOAD_GRAPH,
		QueryHints.JAKARTA_HINT_LOADGRAPH,
		QueryHints.HINT_FETCHGRAPH,
		QueryHints.HINT_LOADGRAPH,
		QueryHints.SPEC_HINT_TIMEOUT
	};
}
