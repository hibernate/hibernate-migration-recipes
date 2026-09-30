package fixture.temporal.temporalsafety.descendantoverloadblocksbaseproperty;

import jakarta.persistence.*;

class Example {
	@Temporal(TemporalType.TIMESTAMP)
	java.util.Date created;

	public final void setCreated(java.util.Date input) {
		created = input;
	}
}

class Child extends Example {
	public void setCreated(java.time.Instant input) {
	}
}
