package fixture.queryhints.already;

import org.hibernate.jpa.*;
import static org.hibernate.jpa.SpecHints.*;

/// Query-hint migration input.
///
/// @author Steve Ebersole
class Example {
	String hibernate = HibernateHints.HINT_READ_ONLY;
	String spec = HINT_SPEC_FETCH_GRAPH;
	String legacy = LegacySpecHints.HINT_JAVAEE_QUERY_TIMEOUT;
}
