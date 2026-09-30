package fixture.temporal.temporalsafety.importedjavanamemustnotproduceinvalidoutput;

import fixture.temporal.temporalsafety.importedjavanamemustnotproduceinvalidoutput.p.java;
import java.util.Date;
import jakarta.persistence.*;

class Example {
	@Temporal(TemporalType.TIMESTAMP)
	Date created;
}
