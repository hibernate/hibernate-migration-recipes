package fixture.queryhints.forms;

import org.hibernate.jpa.QueryHints;
import org.hibernate.jpa.HibernateHints;
import org.hibernate.jpa.SpecHints;

import static org.hibernate.jpa.QueryHints.JAKARTA_HINT_FETCHGRAPH;
import static org.hibernate.jpa.SpecHints.HINT_SPEC_FETCH_GRAPH;

/// Query-hint migration input.
///
/// @author Steve Ebersole
@jakarta.persistence.NamedQuery(name = "hints", query = "from Thing",
		hints = @jakarta.persistence.QueryHint(name = QueryHints.HINT_READONLY, value = "true"))
class Example {
	String first = /* before */ QueryHints /* owner */ . /* field */ HINT_READONLY /* tail */;
	String second = org /* package */ .hibernate.jpa.QueryHints.HINT_NATIVE_LOCKMODE;
	String existing = HibernateHints.HINT_READ_ONLY;
	String graph = JAKARTA_HINT_FETCHGRAPH;
	String existingGraph = HINT_SPEC_FETCH_GRAPH;
	String existingQualified = SpecHints.HINT_SPEC_FETCH_GRAPH;
	String literal = "org.hibernate.jpa.QueryHints.HINT_READONLY";
}
