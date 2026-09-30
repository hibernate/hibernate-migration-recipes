package fixture.jpa4.namedqueryconversion.queryguards;

@jakarta.persistence.NamedNativeQuery(name = "ResultAttributesBeforeReturningClause", query = "delete from t returning id", resultClass = void.class, resultSetMapping = "")
class ResultAttributesBeforeReturningClause {
}
