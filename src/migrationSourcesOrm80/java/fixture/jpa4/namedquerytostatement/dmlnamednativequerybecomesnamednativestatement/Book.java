package fixture.jpa4.namedquerytostatement.dmlnamednativequerybecomesnamednativestatement;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.NamedNativeQuery;

@NamedNativeQuery(name = "Book.nativeDelete", query = "delete from BOOK where year < 2000")
@Entity
class Book {
	@Id
	Long id;
}
