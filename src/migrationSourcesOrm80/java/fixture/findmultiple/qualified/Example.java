package fixture.findmultiple.qualified;

/// @author Steve Ebersole
class Example {
	org.hibernate.OrderingMode ordering = org.hibernate.OrderingMode.UNORDERED;
	org.hibernate.SessionCheckMode checking = org.hibernate.SessionCheckMode.DISABLED;
	org.hibernate.RemovalsMode removals = org.hibernate.RemovalsMode.REPLACE;
	org.hibernate.BatchSize batch = new org.hibernate.BatchSize(5);
	String name = "org.hibernate.BatchSize";
}
