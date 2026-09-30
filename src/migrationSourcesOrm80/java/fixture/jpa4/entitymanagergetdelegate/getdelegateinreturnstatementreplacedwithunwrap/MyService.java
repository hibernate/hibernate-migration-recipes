package fixture.jpa4.entitymanagergetdelegate.getdelegateinreturnstatementreplacedwithunwrap;

import jakarta.persistence.EntityManager;

class MyService {
	Object getUnderlying(EntityManager em) {
		return em.getDelegate();
	}
}
