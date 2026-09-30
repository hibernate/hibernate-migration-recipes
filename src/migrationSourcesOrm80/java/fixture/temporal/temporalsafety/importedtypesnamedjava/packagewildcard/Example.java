package fixture.temporal.temporalsafety.importedtypesnamedjava.packagewildcard;

import fixture.temporal.temporalsafety.importedtypesnamedjava.p.*;
import java.util.Date;
import jakarta.persistence.*;

class Example {
	@Temporal(TemporalType.TIMESTAMP)
	Date created;
}
