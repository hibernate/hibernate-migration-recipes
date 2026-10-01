package fixture.queryhints.unsupported;

import org.hibernate.jpa.QueryHints;

import static org.hibernate.jpa.QueryHints.HINT_READONLY;
import static org.hibernate.jpa.QueryHints.getDefinedHints;

/// Query-hint migration input.
///
/// @author Steve Ebersole
class Example {
	QueryHints receiver;
	Class<?> type = QueryHints.class;
	Object hints = QueryHints.getDefinedHints();
	Object bareHints = getDefinedHints();
	java.util.function.Supplier<java.util.Set<String>> supplier = QueryHints::getDefinedHints;
	String instance = receiver.HINT_READONLY;
	String evaluated = receiver().HINT_READONLY;
	String supported = HINT_READONLY;
	QueryHints receiver() {
		return receiver;
	}
}
