package fixture.jpa4.namedqueryconversion.queryguards;

@jakarta.persistence.NamedQuery(name = "LeadingLineComment", query = "--lead\ndelete from t")
class LeadingLineComment {
}
