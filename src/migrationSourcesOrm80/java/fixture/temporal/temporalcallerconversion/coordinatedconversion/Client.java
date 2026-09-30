package fixture.temporal.temporalcallerconversion.coordinatedconversion;

class Client {
	void use(Entity e, java.util.Date input) {
		e.setCreated(input);
		java.util.Date first = e.getCreated();
		final java.util.Date alias = first;
		if (alias != null)
			e.setCreated(alias);
	}
}
