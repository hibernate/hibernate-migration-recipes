package fixture.temporal.temporalconfiguration.localvaluepolicy;

import jakarta.persistence.*;

class Example {
	@Temporal(TemporalType.DATE)
	java.util.Date day = new java.util.Date(0);
	@Temporal(TemporalType.TIME)
	java.util.Calendar time = java.util.Calendar.getInstance();
	@Temporal(TemporalType.TIMESTAMP)
	java.util.Date stamp = new java.util.Date(0);
}
