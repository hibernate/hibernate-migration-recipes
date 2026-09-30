package fixture.findmultiple.conflicts;

import org.hibernate.annotations.BatchSize;

/// @author Steve Ebersole
@BatchSize(size = 20)
class Example {
	static class OrderingMode {}
	static class SessionCheckMode {}
	static class RemovalsMode {}
	static class FindMultipleOption {}
	OrderingMode unrelatedOrdering;
	SessionCheckMode unrelatedChecking;
	RemovalsMode unrelatedRemovals;
	org.hibernate.OrderingMode ordering = org.hibernate.OrderingMode.ORDERED;
	org.hibernate.SessionCheckMode checking = org.hibernate.SessionCheckMode.ENABLED;
	org.hibernate.RemovalsMode removals = org.hibernate.RemovalsMode.INCLUDE;
	org.hibernate.BatchSize batch = new org.hibernate.BatchSize(7);
	static class Names {
		Names hibernate = this;
		int BatchSize = 8;
	}
	int unrelatedValue() {
		Names org = new Names();
		return org.hibernate.BatchSize;
	}
}
