package fixture.queryhints.inherited;

import org.hibernate.jpa.QueryHints;

import static org.hibernate.jpa.QueryHints.HINT_READONLY;

/// Query-hint migration input.
///
/// @author Steve Ebersole
class Example extends Parent {
	String hint = QueryHints.HINT_READONLY;
	String bare = HINT_READONLY;
}
class Parent {
	static class HibernateHints {}
	String HINT_READ_ONLY = "unrelated";
}
