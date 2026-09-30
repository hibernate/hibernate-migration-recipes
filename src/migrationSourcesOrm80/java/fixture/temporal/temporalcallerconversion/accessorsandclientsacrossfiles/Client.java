package fixture.temporal.temporalcallerconversion.accessorsandclientsacrossfiles;

class Client {
	void use(Entity e, java.util.Date legacy) {
		e.setCreated(legacy);
		java.util.Date first = e.getCreated();
		final java.util.Date alias = first;
		if (alias != null)
			e.setCreated(alias);
		e.getCreated();
	}
}
