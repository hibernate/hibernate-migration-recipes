package fixture.temporal.temporalpropertyconversion.otherannotationscommentsnullandqualifiedsyntax;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;

class Example {
	@Basic
	@Column(name = "CREATED")
	/* keep */ @jakarta.persistence.Temporal(/* precision */ value = jakarta.persistence.TemporalType.TIMESTAMP)
	java.util.Date created = null;
}
