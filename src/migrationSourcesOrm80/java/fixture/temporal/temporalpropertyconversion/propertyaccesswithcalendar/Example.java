package fixture.temporal.temporalpropertyconversion.propertyaccesswithcalendar;

import jakarta.persistence.*;

@Embeddable
@Access(AccessType.PROPERTY)
class Example {
	private java.util.Calendar stamp;

	@Temporal(/* retain precision */ TemporalType.TIMESTAMP)
	@Column(name = "stamp")
	public java.util.Calendar getStamp() {
		return (this.stamp);
	}

	public void setStamp(java.util.Calendar input) {
		this.stamp = (input);
	}

	void use(java.util.Calendar input) {
		setStamp(input);
		var value = getStamp();
		if (value != null)
			setStamp(value);
	}
}
