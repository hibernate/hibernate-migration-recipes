package fixture.jpa4.namedquerytostatement.selectnamedqueryisnotchanged;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.NamedQuery;

@NamedQuery(name = "Book.findAll", query = "select b from Book b")
@Entity
class Book {
	@Id
	Long id;
}
