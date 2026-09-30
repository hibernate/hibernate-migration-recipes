package fixture.temporal.temporalsafety.samepackagejavaname.p;

import java.util.Date;
import jakarta.persistence.*;

class Example {
	@Temporal(TemporalType.TIMESTAMP)
	Date created;
}
