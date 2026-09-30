package fixture.temporal.temporalpropertyconversion.initializersandassignments;

import jakarta.persistence.*;
import java.util.Date;
import java.util.Calendar;

class Example {
	@Temporal(TemporalType.TIMESTAMP)
	Date stamp = new Date(0);
	@Temporal(TemporalType.TIMESTAMP)
	Calendar calendar = Calendar.getInstance();

	void assign(Date value, Calendar other) {
		stamp = value;
		calendar = other;
	}
}
