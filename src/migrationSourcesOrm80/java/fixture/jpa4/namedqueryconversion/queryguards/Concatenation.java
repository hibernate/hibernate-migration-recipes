package fixture.jpa4.namedqueryconversion.queryguards;

@jakarta.persistence.NamedQuery(name = "Concatenation", query = "delete " + "from t")
class Concatenation {
}
