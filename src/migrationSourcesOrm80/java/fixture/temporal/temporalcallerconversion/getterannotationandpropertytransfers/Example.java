package fixture.temporal.temporalcallerconversion.getterannotationandpropertytransfers;

import jakarta.persistence.*;

class Example {
	java.util.Date first;
	@Temporal(TemporalType.TIMESTAMP)
	java.util.Date second;

	@Temporal(TemporalType.TIMESTAMP)
	public java.util.Date getFirst() {
		return first;
	}

	public void setFirst(java.util.Date first) {
		this.first = first;
	}

	public java.util.Date getSecond() {
		return second;
	}

	public void setSecond(java.util.Date second) {
		this.second = second;
	}

	void transfer(Example e) {
		setFirst(e.getSecond());
		e.setSecond(getFirst());
	}
}
