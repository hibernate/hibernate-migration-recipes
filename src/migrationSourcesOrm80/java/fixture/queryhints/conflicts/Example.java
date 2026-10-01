package fixture.queryhints.conflicts;

import org.hibernate.jpa.QueryHints;

import static org.hibernate.jpa.QueryHints.HINT_READONLY;
import static org.hibernate.jpa.QueryHints.JAKARTA_HINT_FETCHGRAPH;

/// Query-hint migration input.
///
/// @author Steve Ebersole
class Example {
	static class HibernateHints {}
	static class SpecHints {}
	static class LegacySpecHints {}
	String HINT_READ_ONLY = "unrelated";
	String HINT_SPEC_FETCH_GRAPH = "unrelated";
	String first = QueryHints.HINT_READONLY;
	String second = QueryHints.JAKARTA_HINT_FETCHGRAPH;
	String third = QueryHints.HINT_FETCHGRAPH;
	String fourth = HINT_READONLY;
	String fifth = JAKARTA_HINT_FETCHGRAPH;
}
