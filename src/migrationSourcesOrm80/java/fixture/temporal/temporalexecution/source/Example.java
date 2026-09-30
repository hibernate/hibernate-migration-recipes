package fixture.temporal.temporalexecution.source;

import jakarta.persistence.*;

class Example {
	@Temporal(TemporalType.TIMESTAMP)
	java.util.Date created = new java.util.Date(0);
}
