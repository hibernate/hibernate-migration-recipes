package fixture.findmultiple.unrelated;

import org.hibernate.annotations.BatchSize;

/// @author Steve Ebersole
@BatchSize(size = 30)
class Example {
	static class OrderingMode {}
	static class SessionCheckMode {}
	static class RemovalsMode {}
	OrderingMode ordering;
	SessionCheckMode checking;
	RemovalsMode removals;
	String name = "org.hibernate.OrderingMode";
}
