package fixture.jpa4.namedqueryconversion.queryguards;

@jakarta.persistence.NamedNativeQuery(name = "DollarQuote", query = "update t set x=$$output$$")
class DollarQuote {
}
