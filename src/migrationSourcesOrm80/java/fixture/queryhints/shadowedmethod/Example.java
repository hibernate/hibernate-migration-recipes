package fixture.queryhints.shadowedmethod;

import static org.hibernate.jpa.QueryHints.getDefinedHints;

/// Query-hint migration input.
///
/// @author Steve Ebersole
class Example {
	String value = getDefinedHints();
	static String getDefinedHints() {
		return "custom";
	}
}
