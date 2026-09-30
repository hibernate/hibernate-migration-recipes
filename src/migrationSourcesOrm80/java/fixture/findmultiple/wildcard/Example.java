package fixture.findmultiple.wildcard;

import org.hibernate.*;

/// @author Steve Ebersole
class Example {
	OrderingMode ordering = OrderingMode.ORDERED;
	SessionCheckMode checking = SessionCheckMode.ENABLED;
	RemovalsMode removals = RemovalsMode.INCLUDE;
	BatchSize batch = new BatchSize(3);
	Session session;
}
