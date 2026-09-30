package fixture.temporal.temporalconfiguration.declarationonlytargetsandnullassignmentsneednozone;

import jakarta.persistence.*;

class Example {
	@Temporal(TemporalType.TIMESTAMP)
	java.util.Date value;

	void clear() {
		value = null;
	}
}
