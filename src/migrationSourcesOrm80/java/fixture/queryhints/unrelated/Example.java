package fixture.queryhints.unrelated;



/// Query-hint migration input.
///
/// @author Steve Ebersole
class Example {
	static class QueryHints {
		static final String HINT_READONLY = "custom";
	}
	String hint = QueryHints.HINT_READONLY;
	Class<?> type = QueryHints.class;
}
