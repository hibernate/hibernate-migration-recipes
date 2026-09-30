package fixture.jpa4.namedqueryconversion.queryguards;

@jakarta.persistence.NamedNativeQuery(name = "OracleQuote", query = "update t set x=q'[text]'")
class OracleQuote {
}
