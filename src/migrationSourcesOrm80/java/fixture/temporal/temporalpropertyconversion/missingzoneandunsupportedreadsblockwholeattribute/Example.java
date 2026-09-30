package fixture.temporal.temporalpropertyconversion.missingzoneandunsupportedreadsblockwholeattribute;

import jakarta.persistence.*;
import java.util.Date;

class Example {
	@Temporal(TemporalType.DATE)
	Date missing = new Date();
	@Temporal(TemporalType.TIMESTAMP)
	Date read = new Date();

	Date getRead() {
		return new Date(read.getTime());
	}

	void setRead(Date value) {
		read = value;
	}
}
