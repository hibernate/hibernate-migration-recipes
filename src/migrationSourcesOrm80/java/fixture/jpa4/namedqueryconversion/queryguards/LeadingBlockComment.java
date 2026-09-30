package fixture.jpa4.namedqueryconversion.queryguards;

@jakarta.persistence.NamedQuery(name = "LeadingBlockComment", query = "/*lead*/ delete from t")
class LeadingBlockComment {
}
