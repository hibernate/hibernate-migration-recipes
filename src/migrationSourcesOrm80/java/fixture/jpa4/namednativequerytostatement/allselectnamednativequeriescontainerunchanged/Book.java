package fixture.jpa4.namednativequerytostatement.allselectnamednativequeriescontainerunchanged;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.NamedNativeQueries;
import jakarta.persistence.NamedNativeQuery;

@NamedNativeQueries({
		@NamedNativeQuery(name = "Book.findAll", query = "SELECT * FROM BOOK"),
		@NamedNativeQuery(name = "Book.findById", query = "SELECT * FROM BOOK WHERE id = :id")
})
@Entity
class Book {
	@Id
	Long id;
}
