package fixture.temporal.temporalcallerconversion.blockedcrossfileorderhasstablediagnostics;

import jakarta.persistence.*;

class Entity {
	@Temporal(TemporalType.TIMESTAMP)
	java.util.Date created;

	java.util.Date getCreated() {
		return created;
	}
}
