package fixture.jpa4.namedquerytostatement.dmlnamedquerybecomesnamedstatement;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.NamedQuery;

@NamedQuery(name = "Book.deleteOld", query = "delete from Book where year < 2000")
@Entity
class Book {
	@Id
	Long id;
}
