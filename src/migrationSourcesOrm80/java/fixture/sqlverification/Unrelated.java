package fixture.sqlverification;

/// SQL verification migration input.
/// @author Steve Ebersole
@Unrelated.SQLInsert(check = Unrelated.ResultCheckStyle.COUNT)
class Unrelated {
	@interface SQLInsert {
		ResultCheckStyle check();
	}
	enum ResultCheckStyle { COUNT }
}
