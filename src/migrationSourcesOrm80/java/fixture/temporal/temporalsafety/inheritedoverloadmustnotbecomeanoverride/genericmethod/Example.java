package fixture.temporal.temporalsafety.inheritedoverloadmustnotbecomeanoverride.genericmethod;

import jakarta.persistence.*;

class Parent<T> {
	public void setCreated(T input) {
	}
}

class Example extends Parent<java.time.Instant> {
	@Temporal(TemporalType.TIMESTAMP)
	java.util.Date created;

	public void setCreated(java.util.Date input) {
		created = input;
	}
}
