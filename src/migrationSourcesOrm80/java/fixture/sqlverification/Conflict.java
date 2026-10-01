package fixture.sqlverification;

import org.hibernate.annotations.SQLInsert;
import org.hibernate.annotations.ResultCheckStyle;

/// SQL verification migration input.
/// @author Steve Ebersole
class Conflict {
	@SQLInsert(sql = "statement", check = ResultCheckStyle.COUNT)
	Object value;

	static class Expectation {}
}
