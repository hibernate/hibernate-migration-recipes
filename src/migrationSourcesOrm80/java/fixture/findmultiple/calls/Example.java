package fixture.findmultiple.calls;

import java.util.List;
import java.util.function.IntFunction;
import org.hibernate.BatchSize;
import org.hibernate.OrderingMode;
import org.hibernate.RemovalsMode;
import org.hibernate.Session;
import org.hibernate.SessionCheckMode;
import static org.hibernate.OrderingMode.ORDERED;
import static org.hibernate.OrderingMode.*;
import static org.hibernate.SessionCheckMode.*;
import static org.hibernate.RemovalsMode.*;

/// @author Steve Ebersole
class Example {
	OrderingMode ordering = ORDERED;
	SessionCheckMode checking = DISABLED;
	RemovalsMode removals = REPLACE;
	BatchSize batch = new BatchSize(16);
	IntFunction<BatchSize> factory = BatchSize::new;
	OrderingMode[] modes = OrderingMode.values();
	Class<BatchSize> batchType = BatchSize.class;
	List<RemovalsMode> removalModes = List.of(INCLUDE, REPLACE, EXCLUDE);
	Class<SessionCheckMode> checkType = SessionCheckMode.class;

	List<Example> load(Session session, List<Integer> ids) {
		// Keep the supplied options and their order.
		return session.findMultiple(Example.class, ids, UNORDERED, ENABLED, EXCLUDE, new BatchSize(batch.batchSize()));
	}

	List<Example> loadDefaults(Session session, List<Integer> ids) {
		return session.findMultiple(Example.class, ids);
	}

	OrderingMode option(OrderingMode input) {
		return input;
	}

	int code(OrderingMode input) {
		return switch (input) {
			case ORDERED -> 1;
			case UNORDERED -> 2;
		};
	}
}
