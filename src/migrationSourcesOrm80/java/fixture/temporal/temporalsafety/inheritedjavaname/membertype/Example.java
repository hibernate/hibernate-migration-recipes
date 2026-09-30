package fixture.temporal.temporalsafety.inheritedjavaname.membertype;

import java.util.Date;
import jakarta.persistence.*;

class Example extends fixture.temporal.temporalsafety.inheritedjavaname.membertype.p.Parent {
	@Temporal(TemporalType.TIMESTAMP)
	Date created = new Date();
}
