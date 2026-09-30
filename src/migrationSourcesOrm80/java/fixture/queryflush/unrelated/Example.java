package fixture.queryflush.unrelated;

class Example {
	enum QueryFlushMode {
		FLUSH, NO_FLUSH, DEFAULT
	}

	QueryFlushMode mode = QueryFlushMode.DEFAULT;
}
