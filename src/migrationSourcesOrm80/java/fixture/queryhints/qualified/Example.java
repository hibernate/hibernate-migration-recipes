package fixture.queryhints.qualified;

import jakarta.persistence.Query;

/// Query-hint migration input.
///
/// @author Steve Ebersole
class Example {
	void apply(Query query) {
		query.setHint(org.hibernate.jpa.QueryHints.HINT_COMMENT, "value");
		query.setHint(org.hibernate.jpa.QueryHints.HINT_FETCH_SIZE, "value");
		query.setHint(org.hibernate.jpa.QueryHints.HINT_CACHEABLE, "value");
		query.setHint(org.hibernate.jpa.QueryHints.HINT_CACHE_REGION, "value");
		query.setHint(org.hibernate.jpa.QueryHints.HINT_CACHE_MODE, "value");
		query.setHint(org.hibernate.jpa.QueryHints.HINT_READONLY, "value");
		query.setHint(org.hibernate.jpa.QueryHints.HINT_FLUSH_MODE, "value");
		query.setHint(org.hibernate.jpa.QueryHints.HINT_NATIVE_LOCKMODE, "value");
		query.setHint(org.hibernate.jpa.QueryHints.HINT_LIMIT_IN_MEMORY, "value");
		query.setHint(org.hibernate.jpa.QueryHints.HINT_FOLLOW_ON_STRATEGY, "value");
		query.setHint(org.hibernate.jpa.QueryHints.HINT_FOLLOW_ON_LOCKING, "value");
		query.setHint(org.hibernate.jpa.QueryHints.HINT_NATIVE_SPACES, "value");
		query.setHint(org.hibernate.jpa.QueryHints.HINT_TIMEOUT, "value");
		query.setHint(org.hibernate.jpa.QueryHints.JAKARTA_SPEC_HINT_TIMEOUT, "value");
		query.setHint(org.hibernate.jpa.QueryHints.JAKARTA_HINT_FETCH_GRAPH, "value");
		query.setHint(org.hibernate.jpa.QueryHints.JAKARTA_HINT_FETCHGRAPH, "value");
		query.setHint(org.hibernate.jpa.QueryHints.JAKARTA_HINT_LOAD_GRAPH, "value");
		query.setHint(org.hibernate.jpa.QueryHints.JAKARTA_HINT_LOADGRAPH, "value");
		query.setHint(org.hibernate.jpa.QueryHints.HINT_FETCHGRAPH, "value");
		query.setHint(org.hibernate.jpa.QueryHints.HINT_LOADGRAPH, "value");
		query.setHint(org.hibernate.jpa.QueryHints.SPEC_HINT_TIMEOUT, "value");
	}
}
