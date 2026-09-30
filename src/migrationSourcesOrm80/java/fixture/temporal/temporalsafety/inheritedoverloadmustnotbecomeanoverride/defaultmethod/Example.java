package fixture.temporal.temporalsafety.inheritedoverloadmustnotbecomeanoverride.defaultmethod;

import jakarta.persistence.*;

interface Parent {
	default void setCreated(java.time.Instant input) {
	}
}

class Example implements Parent {
	@Temporal(TemporalType.TIMESTAMP)
	java.util.Date created;

	public void setCreated(java.util.Date input) {
		created = input;
	}
}
