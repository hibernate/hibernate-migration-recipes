package fixture.temporal.temporalsafety.newsignaturemustnotoverrideinheritedfinalmethod;

import jakarta.persistence.*;

class Parent {
	public final void setCreated(java.time.Instant input) {
	}
}

class Example extends Parent {
	@Temporal(TemporalType.TIMESTAMP)
	java.util.Date created;

	public void setCreated(java.util.Date input) {
		created = input;
	}
}
