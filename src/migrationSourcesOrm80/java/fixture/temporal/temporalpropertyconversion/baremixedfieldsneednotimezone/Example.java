package fixture.temporal.temporalpropertyconversion.baremixedfieldsneednotimezone;

import jakarta.persistence.*;
import static jakarta.persistence.TemporalType.*;
import java.util.Date;
import java.util.Calendar;

class Example {
	@Temporal(DATE)
	Date day;
	@Temporal(TIME)
	Date time;
	@Temporal(value = TIMESTAMP)
	Date stamp;
	@Temporal(DATE)
	Calendar calendarDay;
	@Temporal(TIME)
	Calendar calendarTime;
	@Temporal(TIMESTAMP)
	Calendar calendarStamp;
	Date unrelated = new Date();

	Date getUnrelated() {
		return unrelated;
	}
}
