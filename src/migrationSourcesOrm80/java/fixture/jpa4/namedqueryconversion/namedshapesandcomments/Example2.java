package fixture.jpa4.namedqueryconversion.namedshapesandcomments;

/*before*/ @jakarta.persistence.NamedQueries(value = @jakarta.persistence.NamedQuery(name = Names.NAME, /*query*/ query = " uPdAtE Thing set n = :n", hints = @jakarta.persistence.QueryHint(name = "org.hibernate.timeout", value = "5")))
/*later*/ class Example2 {
}
