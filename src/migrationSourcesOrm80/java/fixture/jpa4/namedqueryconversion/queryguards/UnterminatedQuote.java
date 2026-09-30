package fixture.jpa4.namedqueryconversion.queryguards;

@jakarta.persistence.NamedNativeQuery(name = "UnterminatedQuote", query = "update t set x='unterminated")
class UnterminatedQuote {
}
