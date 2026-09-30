package fixture.temporal.temporalsafety.inheritedjavaname.field;

import java.util.Date;
import jakarta.persistence.*;

class Example extends fixture.temporal.temporalsafety.inheritedjavaname.field.p.Parent {
	@Temporal(TemporalType.TIMESTAMP)
	Date created = new Date();
}
