package fixture.temporal.temporalcallerconversion.crossfilewritesareconvertedandreadsblock;

import jakarta.persistence.*;

class Example {
	@Temporal(TemporalType.TIMESTAMP)
	java.util.Date value;
}