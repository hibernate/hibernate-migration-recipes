package fixture.jpa4.namedqueryconversion.queryguards;

@jakarta.persistence.NamedNativeQuery(name = "BackslashQuote", query = "update t set x='a\\b'")
class BackslashQuote {
}
