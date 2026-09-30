package fixture.jpa4.namedquerytostatement.mixednamedqueriescontainersplitsdmlout;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.NamedQueries;
import jakarta.persistence.NamedQuery;

@NamedQueries({
		@NamedQuery(name = "Book.findAll", query = "select b from Book b"),
		@NamedQuery(name = "Book.deleteOld", query = "delete from Book where year < 2000")
})
@Entity
class Book {
	@Id
	Long id;
}
