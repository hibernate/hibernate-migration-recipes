package fixture.sqlverification;

import org.hibernate.annotations.SQLInsert;
import org.hibernate.annotations.SQLUpdate;
import org.hibernate.annotations.SQLDelete;
import static org.hibernate.annotations.ResultCheckStyle.NONE;
import static org.hibernate.annotations.ResultCheckStyle.COUNT;
import static org.hibernate.annotations.ResultCheckStyle.PARAM;

/// SQL verification migration input.
/// @author Steve Ebersole
class Imported {
	@SQLInsert(sql = "a", check = NONE)
	@SQLUpdate(sql = "b", check = COUNT)
	@SQLDelete(sql = "c", check = PARAM)
	Object value;
}
