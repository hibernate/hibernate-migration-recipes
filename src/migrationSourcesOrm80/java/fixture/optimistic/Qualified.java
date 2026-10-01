package fixture.optimistic;

/// Qualified references, unrelated names, and import collisions.
/// @author Steve Ebersole
class Qualified {
	@interface ExcludedFromVersioning {}
	@interface OptimisticLock {
		boolean excluded();
	}
	@org.hibernate.annotations.OptimisticLock(excluded = true)
	String excluded;
	@OptimisticLock(excluded = true)
	String unrelated;
}
