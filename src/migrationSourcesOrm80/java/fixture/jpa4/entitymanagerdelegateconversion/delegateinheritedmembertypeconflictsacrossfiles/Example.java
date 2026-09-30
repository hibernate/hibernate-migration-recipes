package fixture.jpa4.entitymanagerdelegateconversion.delegateinheritedmembertypeconflictsacrossfiles;

import jakarta.persistence.EntityManager;

class Middle extends Base {
}

class Example extends Middle {
	Object call(EntityManager em) {
		return em.getDelegate();
	}
}
