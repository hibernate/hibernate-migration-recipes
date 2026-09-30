package fixture.jpa4.namedqueryconversion.queryguards;

@jakarta.persistence.NamedNativeQuery(name = "OutputClause", query = "update t set x=1 OUTPUT inserted.x")
class OutputClause {
}
