package fixture.jpa4.namedquerytostatement.allselectnamedqueriescontainerunchanged;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.NamedQueries;
import jakarta.persistence.NamedQuery;

@NamedQueries({
		@NamedQuery(name = "Book.findAll", query = "select b from Book b"),
		@NamedQuery(name = "Book.findById", query = "select b from Book b where b.id = :id")
})
@Entity
class Book {
	@Id
	Long id;
}
