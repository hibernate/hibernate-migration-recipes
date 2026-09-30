package fixture.temporal.temporalpropertyconversion.unsupportedformsremainunchanged;

import jakarta.persistence.*;
import java.util.*;

class Example {
	@Temporal(TemporalType.DATE)
	String invalid;
	@Temporal(TemporalType.DATE)
	Date a, b;
	@ElementCollection
	@Temporal(TemporalType.DATE)
	List<Date> days;

	@Temporal(TemporalType.DATE)
	Date getDate() {
		return null;
	}

	@Temporal(TemporalType.TIMESTAMP)
	Date date = new Date();

	long epoch() {
		return date.getTime();
	}

	@Convert(disableConversion = true)
	@Temporal(TemporalType.DATE)
	Date custom;
}
