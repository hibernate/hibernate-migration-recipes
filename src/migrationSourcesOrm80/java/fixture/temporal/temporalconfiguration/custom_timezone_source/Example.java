package fixture.temporal.temporalconfiguration.custom_timezone_source;

import jakarta.persistence.*;
import java.util.*;

class Example {
	@Temporal(TemporalType.TIMESTAMP)
	Calendar stamp = Calendar.getInstance(new SimpleTimeZone(0, "custom"));
}
