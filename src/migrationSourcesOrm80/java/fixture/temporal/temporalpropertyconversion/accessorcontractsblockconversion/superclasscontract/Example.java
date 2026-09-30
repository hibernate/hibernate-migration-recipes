package fixture.temporal.temporalpropertyconversion.accessorcontractsblockconversion.superclasscontract;

import jakarta.persistence.*;

class Parent {
	public java.util.Date getCreated() {
		return null;
	}
}

class Example extends Parent {
	@Temporal(TemporalType.TIMESTAMP)
	java.util.Date created;

	public java.util.Date getCreated() {
		return created;
	}

	public void setCreated(java.util.Date input) {
		created = input;
	}
}
