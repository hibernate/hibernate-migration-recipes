package fixture.queryflush.qualified;

class Example {
	enum QueryFlushMode {
		FLUSH
	}

	QueryFlushMode unrelated = QueryFlushMode.FLUSH;
	org.hibernate.query.QueryFlushMode mode = org.hibernate.query.QueryFlushMode.NO_FLUSH;
	String name = "org.hibernate.query.QueryFlushMode";
}
