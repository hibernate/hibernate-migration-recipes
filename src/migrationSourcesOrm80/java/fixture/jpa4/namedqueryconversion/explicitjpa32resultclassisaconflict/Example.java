package fixture.jpa4.namedqueryconversion.explicitjpa32resultclassisaconflict;

@jakarta.persistence.NamedQuery(name = "x", query = "delete from Thing", resultClass = void.class)
class Example {
}