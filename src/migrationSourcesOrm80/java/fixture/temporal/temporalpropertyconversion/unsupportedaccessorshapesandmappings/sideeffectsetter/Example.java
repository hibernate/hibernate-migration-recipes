package fixture.temporal.temporalpropertyconversion.unsupportedaccessorshapesandmappings.sideeffectsetter;

import jakarta.persistence.*;

class Example {
	@Temporal(TemporalType.TIMESTAMP)
	java.util.Date created;

	void setCreated(java.util.Date v) {
		System.nanoTime();
		created = v;
	}
}
