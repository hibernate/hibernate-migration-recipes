package fixture.sqlverification;

import org.hibernate.annotations.*;
import org.hibernate.jdbc.Expectation;
import static org.hibernate.annotations.ResultCheckStyle.*;

/// SQL verification migration input.
/// @author Steve Ebersole
class Forms {
	@SQLInserts({
		@SQLInsert(sql = "a", check = NONE),
		@SQLInsert(sql = "b", check = COUNT)
	})
	@SQLUpdates({@SQLUpdate(sql = "c", check = PARAM)})
	@SQLDeletes({@SQLDelete(sql = "d", check = COUNT)})
	static class Containers {}

	@org.hibernate.annotations.SQLDeleteAll(sql = "e", check = org.hibernate.annotations.ResultCheckStyle.NONE)
	Object qualified;

	@SQLInsert(sql = "first", check = /* before */ ResultCheckStyle./* inside */COUNT /* tail */, verify = Expectation.None.class)
	Object explicit;

	@SQLUpdate(check = ResultCheckStyle.PARAM, verify = Expectation.class, sql = "sentinel")
	Object sentinel;

	@SQLDelete(sql = "last", verify = Custom.class, check = /* last check */ ResultCheckStyle.COUNT /* closing */)
	Object custom;

	@SQLDeleteAll(sql = "explicit", check = ResultCheckStyle.NONE, verify = Expectation.RowCount.class)
	Object deleteAll;

	@SQLInsert(sql = "replacement", /* key */ check /* equals */ = /* value */ ResultCheckStyle./* constant */COUNT /* after */)
	Object commented;

	@SQLUpdate(sql = "unchanged")
	Object omitted;

	@SQLDelete(sql = "already", verify = Expectation.RowCount.class)
	Object migrated;

	static class Custom extends Expectation.None {}
}
