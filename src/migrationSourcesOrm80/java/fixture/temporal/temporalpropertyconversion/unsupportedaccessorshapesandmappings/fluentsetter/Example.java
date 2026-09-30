package fixture.temporal.temporalpropertyconversion.unsupportedaccessorshapesandmappings.fluentsetter;

import jakarta.persistence.*;

class Example {
	@Temporal(TemporalType.TIMESTAMP)
	java.util.Date created;

	Example setCreated(java.util.Date v) {
		created = v;
		return this;
	}
}
