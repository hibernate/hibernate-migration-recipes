package fixture.sqlverification;

import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.ResultCheckStyle;
import static org.hibernate.annotations.ResultCheckStyle.COUNT;

/// SQL verification migration input.
/// @author Steve Ebersole
class Retained {
	@SQLDelete(sql = "statement", check = COUNT)
	Object value;
	ResultCheckStyle retained = COUNT;
}
