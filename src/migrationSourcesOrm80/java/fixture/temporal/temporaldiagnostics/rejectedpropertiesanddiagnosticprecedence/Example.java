package fixture.temporal.temporaldiagnostics.rejectedpropertiesanddiagnosticprecedence;

import jakarta.persistence.*;

class Parent {
	public final void setCreated(java.time.Instant value) {
	}
}

class Example extends Parent {
	@Temporal(TemporalType.DATE)
	java.util.Date missing = new java.util.Date();
	@Temporal(TemporalType.TIMESTAMP)
	java.util.Date created;

	public void setCreated(java.util.Date value) {
		created = value;
	}

	@Temporal(TemporalType.TIMESTAMP)
	java.util.Date first;
	@Temporal(TemporalType.TIMESTAMP)
	java.util.Date second;

	java.util.Date getFirst() {
		return first;
	}

	void setSecond(java.util.Date value) {
		second = value;
	}

	void use() {
		setSecond(getFirst());
		getFirst().setTime(1);
	}

	@Temporal(TemporalType.DATE)
	java.util.Date getComputed() {
		return new java.util.Date();
	}
}
