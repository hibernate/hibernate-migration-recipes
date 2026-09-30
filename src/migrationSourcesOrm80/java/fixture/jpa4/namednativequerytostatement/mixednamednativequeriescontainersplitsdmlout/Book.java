package fixture.jpa4.namednativequerytostatement.mixednamednativequeriescontainersplitsdmlout;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.NamedNativeQueries;
import jakarta.persistence.NamedNativeQuery;

@NamedNativeQueries({
		@NamedNativeQuery(name = "Book.findAll", query = "SELECT * FROM BOOK"),
		@NamedNativeQuery(name = "Book.deleteOld", query = "DELETE FROM BOOK WHERE year < 2000")
})
@Entity
class Book {
	@Id
	Long id;
}
