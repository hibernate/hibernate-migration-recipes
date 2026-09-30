package fixture.jpa4.namedqueryconversion.queryguards;

@jakarta.persistence.NamedNativeQuery(name = "ReturningClause", query = "delete from t returning id")
class ReturningClause {
}
