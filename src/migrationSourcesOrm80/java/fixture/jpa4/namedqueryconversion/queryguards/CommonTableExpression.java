package fixture.jpa4.namedqueryconversion.queryguards;

@jakarta.persistence.NamedQuery(name = "CommonTableExpression", query = "with c as (select 1) delete from t")
class CommonTableExpression {
}
