package fixture.jpa4.jpa3to4.allthreemigrationsappliedtogether;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.NamedQuery;

@NamedQuery(name = "Book.deleteOld", query = "delete from Book where year < 2000")
@Entity
class Book {
	@Id
	Long id;

	void clean(EntityManager em) {
		Object delegate = em.getDelegate();
	}
}
