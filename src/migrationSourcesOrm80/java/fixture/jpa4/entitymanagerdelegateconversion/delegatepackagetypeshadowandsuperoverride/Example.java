package fixture.jpa4.entitymanagerdelegateconversion.delegatepackagetypeshadowandsuperoverride;

import jakarta.persistence.EntityManager;

abstract class Example extends Base {
	Object inherited() {
		return super.getDelegate();
	}

	Object call(EntityManager em) {
		return em.getDelegate();
	}
}

class java {
}

abstract class Base implements EntityManager {
	public Object getDelegate() {
		return this;
	}
}
