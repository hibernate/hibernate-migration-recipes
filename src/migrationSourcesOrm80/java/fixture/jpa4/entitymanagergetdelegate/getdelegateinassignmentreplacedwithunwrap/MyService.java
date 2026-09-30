package fixture.jpa4.entitymanagergetdelegate.getdelegateinassignmentreplacedwithunwrap;

import jakarta.persistence.EntityManager;

class MyService {
	void method(EntityManager em) {
		Object delegate = em.getDelegate();
	}
}
