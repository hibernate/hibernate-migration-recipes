package fixture.temporal.temporalpropertyconversion.unrelatedlocaltypesandnameconflictsremainunchanged;

import jakarta.persistence.*;
import java.util.Date;

class Example<java> {
	@Temporal(TemporalType.TIMESTAMP)
	Date value;
}
