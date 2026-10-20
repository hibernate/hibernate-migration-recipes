package fixture.loadaccess;

import java.util.List;
import org.hibernate.Session;
import static org.hibernate.CacheMode.*;

/// Attribution and array-adaptation inputs, including inherited calls and conflicting names.
/// @author Steve Ebersole
class Advanced {
    static class Optional {}
    static class KeyType {}
    static class ReadOnlyMode {}
    java.util.Optional<Advanced> conflicts(Session s, Long id) { return s.byId(Advanced.class).loadOptional(id); }
    Advanced statics(Session s, Long id) { return s.byId(Advanced.class).with(GET).load(id); }
    List<Advanced> explicitArrayKey(Session s, Object[] keys) { return s.byMultipleIds(Advanced.class).<Object[]>multiLoad(keys); }
    List<Advanced> primitiveArrayKey(Session s, int[] key) { return s.byMultipleIds(Advanced.class).multiLoad(key); }
    List<Advanced> castKey(Session s, Object[] key) { return s.byMultipleNaturalId(Advanced.class).multiLoad((Object) key); }
    Advanced nullableValue(Session s) { return s.byNaturalId(Advanced.class).using("code", null).load(); }
    Advanced optionComments(Session s, Long id) { return s.byId(Advanced.class).withReadOnly(/*flag*/true).load(id); }
    List<Advanced> batchComment(Session s, List<Long> keys) { return s.byMultipleIds(Advanced.class).withBatchSize(/*batch*/2).multiLoad(keys); }
    interface Inherited extends Session {
        default Advanced inherited(Long id) { return byId(Advanced.class).load(id); }
    }
}
