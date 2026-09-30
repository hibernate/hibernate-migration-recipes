package fixture.jpa4.namedquerytostatement.alldmlnamedqueriescontainerfullyreplaced;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.NamedQueries;
import jakarta.persistence.NamedQuery;

@NamedQueries({
		@NamedQuery(name = "Book.deleteOld", query = "delete from Book where year < 2000"),
		@NamedQuery(name = "Book.updateTitle", query = "update Book set title = :t where id = :id")
})
@Entity
class Book {
	@Id
	Long id;
}
