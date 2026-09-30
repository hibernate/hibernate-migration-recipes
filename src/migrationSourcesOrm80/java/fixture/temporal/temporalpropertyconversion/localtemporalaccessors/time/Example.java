package fixture.temporal.temporalpropertyconversion.localtemporalaccessors.time;

import jakarta.persistence.*;

class Example {
	@Temporal(TemporalType.TIME)
	java.util.Date value;

	java.util.Date getValue() {
		return value;
	}

	void setValue(java.util.Date input) {
		value = input;
	}

	void use(java.util.Date input) {
		setValue(input);
		var v = getValue();
		if (v != null)
			setValue(v);
	}
}
