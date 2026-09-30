package fixture.jpa4.namednativequerytostatement.alldmlnamednativequeriescontainerfullyreplaced;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.NamedNativeQueries;
import jakarta.persistence.NamedNativeQuery;

@NamedNativeQueries({
		@NamedNativeQuery(name = "Book.nativeDelete", query = "DELETE FROM BOOK WHERE year < 2000"),
		@NamedNativeQuery(name = "Book.nativeUpdate", query = "UPDATE BOOK SET title = :t WHERE id = :id")
})
@Entity
class Book {
	@Id
	Long id;
}
