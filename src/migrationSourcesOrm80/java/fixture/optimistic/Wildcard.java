package fixture.optimistic;

import org.hibernate.annotations.*;

/// Resolves a wildcard-imported exclusion annotation.
/// @author Steve Ebersole
class Wildcard {
	@OptimisticLock(excluded = true)
	String excluded;
}
