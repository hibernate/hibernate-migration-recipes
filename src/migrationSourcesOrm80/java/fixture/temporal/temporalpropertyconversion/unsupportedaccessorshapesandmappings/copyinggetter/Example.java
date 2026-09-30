package fixture.temporal.temporalpropertyconversion.unsupportedaccessorshapesandmappings.copyinggetter;

import jakarta.persistence.*;

class Example {
	@Temporal(TemporalType.TIMESTAMP)
	java.util.Date created;

	java.util.Date getCreated() {
		return new java.util.Date(created.getTime());
	}
}
