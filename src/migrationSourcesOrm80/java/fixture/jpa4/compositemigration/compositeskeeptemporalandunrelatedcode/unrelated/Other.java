package fixture.jpa4.compositemigration.compositeskeeptemporalandunrelatedcode.unrelated;

@interface MapKey {
	String name();
}

class Other {
	@MapKey(name = "id")
	Object value;
}
