package fixture.temporal.temporalsafety.importednameconflict;

import fixture.temporal.temporalsafety.importednameconflict.p.java;
import java.util.Date;
import jakarta.persistence.*;

class Example {
	@Temporal(TemporalType.TIMESTAMP)
	Date created;
}
