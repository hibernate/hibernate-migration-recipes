package fixture.queryhints.statics;

import static org.hibernate.jpa.QueryHints.HINT_COMMENT;
import static org.hibernate.jpa.QueryHints.HINT_FETCH_SIZE;
import static org.hibernate.jpa.QueryHints.HINT_CACHEABLE;
import static org.hibernate.jpa.QueryHints.HINT_CACHE_REGION;
import static org.hibernate.jpa.QueryHints.HINT_CACHE_MODE;
import static org.hibernate.jpa.QueryHints.HINT_READONLY;
import static org.hibernate.jpa.QueryHints.HINT_FLUSH_MODE;
import static org.hibernate.jpa.QueryHints.HINT_NATIVE_LOCKMODE;
import static org.hibernate.jpa.QueryHints.HINT_LIMIT_IN_MEMORY;
import static org.hibernate.jpa.QueryHints.HINT_FOLLOW_ON_STRATEGY;
import static org.hibernate.jpa.QueryHints.HINT_FOLLOW_ON_LOCKING;
import static org.hibernate.jpa.QueryHints.HINT_NATIVE_SPACES;
import static org.hibernate.jpa.QueryHints.HINT_TIMEOUT;
import static org.hibernate.jpa.QueryHints.JAKARTA_SPEC_HINT_TIMEOUT;
import static org.hibernate.jpa.QueryHints.JAKARTA_HINT_FETCH_GRAPH;
import static org.hibernate.jpa.QueryHints.JAKARTA_HINT_FETCHGRAPH;
import static org.hibernate.jpa.QueryHints.JAKARTA_HINT_LOAD_GRAPH;
import static org.hibernate.jpa.QueryHints.JAKARTA_HINT_LOADGRAPH;
import static org.hibernate.jpa.QueryHints.HINT_FETCHGRAPH;
import static org.hibernate.jpa.QueryHints.HINT_LOADGRAPH;
import static org.hibernate.jpa.QueryHints.SPEC_HINT_TIMEOUT;

/// Query-hint migration input.
///
/// @author Steve Ebersole
class Example {
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
}
