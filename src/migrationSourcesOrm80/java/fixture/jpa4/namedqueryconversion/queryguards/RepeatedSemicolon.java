package fixture.jpa4.namedqueryconversion.queryguards;

@jakarta.persistence.NamedNativeQuery(name = "RepeatedSemicolon", query = "delete from t;;")
class RepeatedSemicolon {
}
