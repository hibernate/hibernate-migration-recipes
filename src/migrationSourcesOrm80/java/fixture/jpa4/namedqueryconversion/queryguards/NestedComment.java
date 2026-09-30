package fixture.jpa4.namedqueryconversion.queryguards;

@jakarta.persistence.NamedNativeQuery(name = "NestedComment", query = "delete from t /*nested /*no*/ */")
class NestedComment {
}
