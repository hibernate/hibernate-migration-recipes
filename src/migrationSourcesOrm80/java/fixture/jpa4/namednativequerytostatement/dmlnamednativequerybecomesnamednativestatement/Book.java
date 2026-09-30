package fixture.jpa4.namednativequerytostatement.dmlnamednativequerybecomesnamednativestatement;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.NamedNativeQuery;

@NamedNativeQuery(name = "Book.nativeDelete", query = "DELETE FROM BOOK WHERE year < 2000")
@Entity
class Book {
	@Id
	Long id;
}
