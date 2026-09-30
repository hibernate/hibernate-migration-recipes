package fixture.jpa4.entitymanagerdelegateconversion.delegateskips;

import jakarta.persistence.EntityManager;

import java.util.function.Supplier;

abstract class Example implements EntityManager {
	public Object getDelegate() {
		return this;
	}

	Object own() {
		return getDelegate();
	}

	Supplier<Object> ref() {
		return this::getDelegate;
	}
}

class Other {
	Object conflict(EntityManager em, Object java) {
		return em.getDelegate();
	}

	Supplier<Object> ref(EntityManager em) {
		return em::getDelegate;
	}
}
