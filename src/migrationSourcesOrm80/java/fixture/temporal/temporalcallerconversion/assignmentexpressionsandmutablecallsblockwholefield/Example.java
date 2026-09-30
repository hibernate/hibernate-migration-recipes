package fixture.temporal.temporalcallerconversion.assignmentexpressionsandmutablecallsblockwholefield;

import jakarta.persistence.*;

class Example {
	@Temporal(TemporalType.TIMESTAMP)
	java.util.Date value = new java.util.Date();

	Object update() {
		return value = new java.util.Date();
	}

	@Temporal(TemporalType.DATE)
	java.util.Calendar day = java.util.Calendar.getInstance();

	void change() {
		day.add(java.util.Calendar.DAY_OF_MONTH, 1);
	}
}
