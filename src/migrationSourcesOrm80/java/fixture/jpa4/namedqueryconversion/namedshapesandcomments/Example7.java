package fixture.jpa4.namedqueryconversion.namedshapesandcomments;

/*before*/ @jakarta.persistence.NamedNativeQueries(value = {
		/*array*/ @jakarta.persistence.NamedNativeQuery(name = Names.NAME, /*query*/ query = " uPdAtE Thing set n = :n", hints = @jakarta.persistence.QueryHint(name = "org.hibernate.timeout", value = "5")) /*close*/ })
/*later*/ class Example7 {
}
