package fixture.jpa4.namedqueryconversion.queryguards;

@jakarta.persistence.NamedNativeQuery(name = "MultipleStatements", query = "delete from t; delete from t")
class MultipleStatements {
}
