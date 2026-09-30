package fixture.queryflush.calls;

import org.hibernate.query.Query;
import org.hibernate.query.QueryFlushMode;
import org.hibernate.annotations.NamedQuery;
import org.hibernate.annotations.NamedNativeQuery;
import static org.hibernate.query.QueryFlushMode.FLUSH;
import static org.hibernate.query.QueryFlushMode.*;

@NamedQuery(name = "all", query = "from Thing", flush = FLUSH)
@NamedNativeQuery(name = "nativeAll", query = "select * from Thing", flush = NO_FLUSH)
/// Input for the QueryFlushMode calls scenario.
/// @author Steve Ebersole
class Example {
    QueryFlushMode mode = QueryFlushMode.DEFAULT;
    java.util.List<QueryFlushMode> modes = java.util.List.of(FLUSH, NO_FLUSH, mode);
    QueryFlushMode apply(Query<?> query, QueryFlushMode mode) {
        query.setQueryFlushMode(mode);
        return query.getQueryFlushMode();
    }
    Query<?> chained(Query<?> query) {
        return query.setQueryFlushMode(FLUSH).setMaxResults(10);
    }
    Class<QueryFlushMode> type = QueryFlushMode.class;
    QueryFlushMode parsed = QueryFlushMode.valueOf("DEFAULT");
    QueryFlushMode[] values = QueryFlushMode.values();
    int code(QueryFlushMode mode) {
        return switch (mode) {
            case FLUSH -> 1;
            case NO_FLUSH -> 2;
            case DEFAULT -> 3;
        };
    }
}
