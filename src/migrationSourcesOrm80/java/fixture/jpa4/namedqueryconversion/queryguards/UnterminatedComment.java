package fixture.jpa4.namedqueryconversion.queryguards;

@jakarta.persistence.NamedNativeQuery(name = "UnterminatedComment", query = "delete from t /*oops")
class UnterminatedComment {
}
