package fixture.findmultiple.multifile;

import org.hibernate.OrderingMode;
import org.hibernate.BatchSize;

/// @author Steve Ebersole
class Modes {
	OrderingMode option(OrderingMode mode) {
		return mode;
	}

	BatchSize batch(int size) {
		return new BatchSize(size);
	}
}
