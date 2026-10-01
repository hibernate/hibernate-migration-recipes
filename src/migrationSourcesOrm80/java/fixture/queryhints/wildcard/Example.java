package fixture.queryhints.wildcard;

import org.hibernate.jpa.*;

import static org.hibernate.jpa.QueryHints.*;

/// Query-hint migration input.
///
/// @author Steve Ebersole
class Example {
	AvailableHints other;
	String[] hints = {
		HINT_COMMENT,
		HINT_FETCH_SIZE,
		HINT_CACHEABLE,
		HINT_CACHE_REGION,
		HINT_CACHE_MODE,
		HINT_READONLY,
		HINT_FLUSH_MODE,
		HINT_NATIVE_LOCKMODE,
		HINT_LIMIT_IN_MEMORY,
		HINT_FOLLOW_ON_STRATEGY,
		HINT_FOLLOW_ON_LOCKING,
		HINT_NATIVE_SPACES,
		HINT_TIMEOUT,
		JAKARTA_SPEC_HINT_TIMEOUT,
		JAKARTA_HINT_FETCH_GRAPH,
		JAKARTA_HINT_FETCHGRAPH,
		JAKARTA_HINT_LOAD_GRAPH,
		JAKARTA_HINT_LOADGRAPH,
		HINT_FETCHGRAPH,
		HINT_LOADGRAPH,
		SPEC_HINT_TIMEOUT
	};
	String ordinary = QueryHints.HINT_READONLY;
}
