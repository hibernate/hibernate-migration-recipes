package fixture.queryflush.premigrated;

import org.hibernate.annotations.FlushModeType;
import org.hibernate.annotations.NamedQuery;

@NamedQuery(name = "Book.byTitle", query = "from Book where title = :title", flushMode = FlushModeType.MANUAL)
class Book {
	org.hibernate.query.QueryFlushMode fallback = org.hibernate.query.QueryFlushMode.FLUSH;
}
