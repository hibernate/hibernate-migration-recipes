package fixture.temporal.temporalpropertyconversion.unsupportedaccessorshapesandmappings.convertedgetter;

import jakarta.persistence.*;

class Example {
	@Temporal(TemporalType.TIMESTAMP)
	java.util.Date created;

	@Convert
	java.util.Date getCreated() {
		return created;
	}
}
