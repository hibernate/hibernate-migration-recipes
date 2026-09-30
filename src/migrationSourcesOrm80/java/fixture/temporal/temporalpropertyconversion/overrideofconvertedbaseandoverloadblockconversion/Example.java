package fixture.temporal.temporalpropertyconversion.overrideofconvertedbaseandoverloadblockconversion;

import jakarta.persistence.*;

class Example {
	@Temporal(TemporalType.TIMESTAMP)
	java.util.Date created;

	public java.util.Date getCreated() {
		return created;
	}
}

class Child extends Example {
	public java.util.Date getCreated() {
		return new java.util.Date();
	}
}

class Other {
	@Temporal(TemporalType.TIMESTAMP)
	java.util.Date created;

	public void setCreated(java.util.Date input) {
		created = input;
	}

	public void setCreated(java.time.Instant input) {
	}
}
