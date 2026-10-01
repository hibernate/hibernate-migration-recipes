package fixture.sqlverification;

import org.hibernate.annotations.*;
import org.hibernate.jdbc.Expectation;

/// SQL verification migration input.
/// @author Steve Ebersole
class Already {
	@SQLInsert(sql = "a")
	@SQLUpdate(sql = "b", verify = Expectation.RowCount.class)
	@SQLDelete(sql = "c", verify = Expectation.None.class)
	@SQLDeleteAll(sql = "d", verify = Expectation.class)
	Object value;
}
