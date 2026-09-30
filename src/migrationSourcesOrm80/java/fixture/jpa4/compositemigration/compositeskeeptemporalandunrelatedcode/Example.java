package fixture.jpa4.compositemigration.compositeskeeptemporalandunrelatedcode;

import jakarta.persistence.*;

import static jakarta.persistence.TemporalType.*;

@NamedQuery(name = "delete", query = "delete from Thing")
class Example {
	@Temporal(DATE)
	java.util.Date date = new java.util.Date();
	@Temporal(TIME)
	java.util.Calendar time = java.util.Calendar.getInstance();
	@Temporal(TIMESTAMP)
	java.util.Date timestamp;
	java.util.Date unrelated = new java.util.Date(0);

	java.util.Date getDate() {
		return date;
	}

	void setDate(java.util.Date date) {
		this.date = date;
	}

	@MapKey(name = "id")
	Object map;

	Object delegate(EntityManager em) {
		return em.getDelegate();
	}
}
