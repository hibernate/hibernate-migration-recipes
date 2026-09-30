package fixture.temporal.temporalcallerconversion.incompatibletransferblocksonlyconnectedproperties;

import jakarta.persistence.*;

class Example {
	@Temporal(TemporalType.DATE)
	java.util.Date day;
	@Temporal(TemporalType.TIMESTAMP)
	java.util.Date stamp;
	@Temporal(TemporalType.TIME)
	java.util.Date unrelated;

	java.util.Date getDay() {
		return day;
	}

	void setStamp(java.util.Date input) {
		stamp = input;
	}

	void use() {
		setStamp(getDay());
	}
}
