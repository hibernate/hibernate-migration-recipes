package fixture.queryflush.unrelated;

/// Input for the QueryFlushMode unrelated scenario.
/// @author Steve Ebersole
class Example {
    enum QueryFlushMode { FLUSH, NO_FLUSH, DEFAULT }
    QueryFlushMode mode = QueryFlushMode.DEFAULT;
}
