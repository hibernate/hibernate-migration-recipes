package fixture.temporal.temporalcallerconversion.accessorsandclientsacrossfiles;

import jakarta.persistence.*;

class Entity {
	@Temporal(TemporalType.TIMESTAMP)
	private java.util.Date created;

	public java.util.Date getCreated() {
		return this.created;
	}

	public void setCreated(java.util.Date input) {
		this.created = input;
	}
}
