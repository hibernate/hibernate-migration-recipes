package fixture.jpa4.entitymanagerdelegateconversion.delegatedispatch;

import jakarta.persistence.EntityManager;

abstract class Example implements EntityManager {
	Object direct() {
		return getDelegate(/*inside*/ );
	}

	Object explicit() {
		return this.getDelegate();
	}

	EntityManager obtain() {
		return this;
	}

	Object effect() {
		return obtain().getDelegate();
	}

	Object shadow() {
		class Object {
		}
		return getDelegate();
	}
}

class Unrelated {
	Object getDelegate() {
		return null;
	}

	Object call() {
		return getDelegate();
	}
}
