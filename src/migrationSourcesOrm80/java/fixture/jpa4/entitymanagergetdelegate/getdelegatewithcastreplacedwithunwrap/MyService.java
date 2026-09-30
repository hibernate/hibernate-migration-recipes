package fixture.jpa4.entitymanagergetdelegate.getdelegatewithcastreplacedwithunwrap;

import jakarta.persistence.EntityManager;

class MyService {
	void method(EntityManager em) {
		Object delegate = (Object) em.getDelegate();
	}
}
