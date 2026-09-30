package fixture.temporal.temporalcallerconversion.unsupportedclientblocksconnectedproperties;

import jakarta.persistence.*;

class Example {
	@Temporal(TemporalType.TIMESTAMP)
	java.util.Date first;
	@Temporal(TemporalType.TIMESTAMP)
	java.util.Date second;

	java.util.Date getFirst() {
		return first;
	}

	void setSecond(java.util.Date input) {
		second = input;
	}

	void use() {
		setSecond(getFirst());
		getFirst().setTime(1);
	}
}
