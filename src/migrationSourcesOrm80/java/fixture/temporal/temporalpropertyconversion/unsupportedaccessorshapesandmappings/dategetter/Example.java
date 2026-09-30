package fixture.temporal.temporalpropertyconversion.unsupportedaccessorshapesandmappings.dategetter;

import jakarta.persistence.*;

class Example {
	@Temporal(TemporalType.TIMESTAMP)
	java.util.Date created;

	@Temporal(TemporalType.DATE)
	java.util.Date getCreated() {
		return created;
	}
}
