package fixture.jpa4.namednativequerytostatement.selectnamednativequeryisnotchanged;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.NamedNativeQuery;

@NamedNativeQuery(name = "Book.findAll", query = "SELECT * FROM BOOK")
@Entity
class Book {
	@Id
	Long id;
}
