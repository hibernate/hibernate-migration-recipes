package fixture.sqlverification.inherited;

import org.hibernate.annotations.SQLInsert;
import org.hibernate.annotations.ResultCheckStyle;

/// SQL verification migration input.
/// @author Steve Ebersole
class Example extends Parent {
	@SQLInsert(sql = "statement", check = ResultCheckStyle.COUNT)
	Object value;
}
