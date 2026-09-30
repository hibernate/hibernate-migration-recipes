package fixture.jpa4.namednativequerytostatement.updatenamednativequerybecomesnamednativestatement;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.NamedNativeQuery;

@NamedNativeQuery(name = "Book.nativeUpdate", query = "UPDATE BOOK SET title = :t WHERE id = :id")
@Entity
class Book {
	@Id
	Long id;
}
