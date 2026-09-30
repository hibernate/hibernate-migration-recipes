package fixture.temporal.temporalsafety.importedtypesnamedjava.nestedtype;

import fixture.temporal.temporalsafety.importedtypesnamedjava.p.Parent.java;
import java.util.Date;
import jakarta.persistence.*;

class Example {
	@Temporal(TemporalType.TIMESTAMP)
	Date created;
}
