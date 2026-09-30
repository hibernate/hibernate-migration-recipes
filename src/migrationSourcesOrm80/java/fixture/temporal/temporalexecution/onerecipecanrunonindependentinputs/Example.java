package fixture.temporal.temporalexecution.onerecipecanrunonindependentinputs;

import jakarta.persistence.*;

class Example {
	@Temporal(TemporalType.TIMESTAMP)
	java.util.Date created;

	long read() {
		return created.getTime();
	}
}
