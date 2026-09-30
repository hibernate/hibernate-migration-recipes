package fixture.temporal.temporalconfiguration.calendar_source;

import jakarta.persistence.*;

class Example {
	@Temporal(TemporalType.TIMESTAMP)
	java.util.Calendar value = java.util.Calendar.getInstance();
}
