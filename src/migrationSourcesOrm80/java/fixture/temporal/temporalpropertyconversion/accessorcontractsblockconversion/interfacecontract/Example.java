package fixture.temporal.temporalpropertyconversion.accessorcontractsblockconversion.interfacecontract;

import jakarta.persistence.*;

interface Contract {
	java.util.Date getCreated();
}

class Example implements Contract {
	@Temporal(TemporalType.TIMESTAMP)
	java.util.Date created;

	public java.util.Date getCreated() {
		return created;
	}

	public void setCreated(java.util.Date input) {
		created = input;
	}
}
