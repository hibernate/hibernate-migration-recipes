package fixture.jpa4.entitymanagerdelegateconversion.delegateinheritedmembertypeconflictsacrossfiles;

import jakarta.persistence.EntityManager;

class Unrelated {
	Object call(EntityManager em) {
		return em.getDelegate();
	}
}
