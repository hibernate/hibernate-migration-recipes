package fixture.temporal.temporalsafety.inheritedoverloadmustnotbecomeanoverride.staticmethod;

import jakarta.persistence.*;

class Parent {
	public static void setCreated(java.time.Instant input) {
	}
}

class Example extends Parent {
	@Temporal(TemporalType.TIMESTAMP)
	java.util.Date created;

	public void setCreated(java.util.Date input) {
		created = input;
	}
}
