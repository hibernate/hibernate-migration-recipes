package fixture.temporal.temporalcallerconversion.coordinatedconversion;

import jakarta.persistence.*;
import static jakarta.persistence.TemporalType.*;

class Entity {
	@Temporal(DATE)
	java.util.Date day = new java.util.Date(0);
	@Temporal(TIME)
	java.util.Calendar time = java.util.Calendar.getInstance();
	private java.util.Date created;

	@Temporal(/* precision */ TIMESTAMP)
	public java.util.Date getCreated() {
		return created;
	}

	public void setCreated(java.util.Date value) {
		created = value;
	}

	@Temporal(TIMESTAMP)
	java.util.Calendar calendar = java.util.Calendar.getInstance();
}
