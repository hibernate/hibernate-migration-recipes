package fixture.jpa4.namedqueryconversion.queryguards;

@jakarta.persistence.NamedQuery(name = "ExplicitLockMode", query = "delete from t", lockMode = jakarta.persistence.LockModeType.NONE)
class ExplicitLockMode {
}
