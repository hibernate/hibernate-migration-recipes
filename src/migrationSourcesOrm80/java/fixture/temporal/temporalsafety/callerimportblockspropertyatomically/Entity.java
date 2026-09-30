package fixture.temporal.temporalsafety.callerimportblockspropertyatomically;

import jakarta.persistence.*;

class Entity {
	@Temporal(TemporalType.TIMESTAMP)
	java.util.Date created;

	void setCreated(java.util.Date input) {
		created = input;
	}
}
