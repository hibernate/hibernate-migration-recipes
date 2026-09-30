package fixture.jpa4.entitymanagerdelegateconversion.delegatetypeparameterconflicts;

import jakarta.persistence.EntityManager;

class Example<java> {
	Object call(EntityManager em) {
		return em.getDelegate();
	}
}

class MethodExample {
	<java> Object call(EntityManager em) {
		return em.getDelegate();
	}
}
