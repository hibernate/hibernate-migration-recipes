package fixture.temporal.temporalcallerconversion.unsupportedclientsareatomic.reassignedlocal;

import jakarta.persistence.*;

class Example {
	@Temporal(TemporalType.TIMESTAMP)
	java.util.Date created;

	java.util.Date getCreated() {
		return created;
	}

	void setCreated(java.util.Date input) {
		created = input;
	}

	static void consume(Object o) {
	}

	void use(Example e) {
		java.util.Date local = e.getCreated();
		local = new java.util.Date();
	}
}
