package fixture.findmultiple.multifile;

import org.hibernate.OrderingMode;
import org.hibernate.BatchSize;

/// @author Steve Ebersole
class Example {
	OrderingMode ordering = new Modes().option(OrderingMode.ORDERED);
	BatchSize batch = new Modes().batch(12);
}
