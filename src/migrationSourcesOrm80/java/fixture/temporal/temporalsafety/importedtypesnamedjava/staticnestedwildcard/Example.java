package fixture.temporal.temporalsafety.importedtypesnamedjava.staticnestedwildcard;

import static fixture.temporal.temporalsafety.importedtypesnamedjava.p.Parent.*;
import java.util.Date;
import jakarta.persistence.*;

class Example {
	@Temporal(TemporalType.TIMESTAMP)
	Date created;
}
