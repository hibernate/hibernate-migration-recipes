package fixture.temporal.temporalcallerconversion.unsupportedclientsareatomic.methodreference;

import jakarta.persistence.*;

class Example {
	@Temporal(TemporalType.TIMESTAMP)
	java.util.Date created;

	java.util.Date getCreated() {
		return created;
	}

	void setCreated(java.util.Date input) {
		created = input;
	}

	static void consume(Object o) {
	}

	void use(Example e) {
		java.util.function.Supplier<java.util.Date> reference = e::getCreated;
	}
}
