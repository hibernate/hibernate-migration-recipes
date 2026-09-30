package fixture.temporal.temporalconfiguration.nullsetterargumentneedsnotimezone;

import jakarta.persistence.*;

class Example {
	@Temporal(TemporalType.DATE)
	java.util.Date value;

	java.util.Date getValue() {
		return value;
	}

	void setValue(java.util.Date v) {
		value = v;
	}

	void clear() {
		setValue((null));
	}
}
