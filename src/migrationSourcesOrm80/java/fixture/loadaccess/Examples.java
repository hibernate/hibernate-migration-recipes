package fixture.loadaccess;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.hibernate.*;
import jakarta.persistence.Timeout;
import jakarta.persistence.PessimisticLockScope;

/// Compilable inputs covering the supported loader families and options.
/// @author Steve Ebersole
public class Examples {
    public Examples id(Session s, Long id) { return s.byId(Examples.class).load(id); }
    Optional<Examples> optional(Session s, Long id) { return s.byId(Examples.class).loadOptional(id); }
    List<Examples> ids(Session s, List<Long> ids) { return s.byMultipleIds(Examples.class).multiLoad(ids); }
    List<Examples> varargs(Session s, Long[] ids) { return s.byMultipleIds(Examples.class).multiLoad(ids); }
    List<Examples> empty(Session s) { return s.byMultipleIds(Examples.class).multiLoad(); }
    List<Examples> values(Session s) { return s.byMultipleIds(Examples.class).multiLoad(1L, 2L, 1L); }
    Examples natural(Session s, String key) { return s.bySimpleNaturalId(Examples.class).load(key); }
    Optional<Examples> naturalOptional(Session s, String key) { return s.bySimpleNaturalId(Examples.class).loadOptional(key); }
    Examples named(Session s, String key) { return s.byNaturalId(Examples.class).using("code", key).load(); }
    Optional<Examples> map(Session s, Map<String, Object> keys) { return s.byNaturalId(Examples.class).using(keys).loadOptional(); }
    List<Examples> naturals(Session s, List<String> keys) { return s.byMultipleNaturalId(Examples.class).multiLoad(keys); }
    List<Examples> naturalArray(Session s, String[] keys) { return s.byMultipleNaturalId(Examples.class).multiLoad(keys); }
    Examples configured(Session s, Long id, boolean readOnly) {
        return s.byId(Examples.class).with(CacheMode.GET).withReadOnly(readOnly).load(id);
    }
    Examples locked(Session s, Long id) {
        return s.byId(Examples.class).with(LockMode.PESSIMISTIC_WRITE, PessimisticLockScope.EXTENDED)
                .with(Timeout.milliseconds(250)).load(id);
    }
    List<Examples> bulkOptions(Session s, List<Long> ids, boolean enabled) {
        return s.byMultipleIds(Examples.class).enableSessionCheck(enabled).enableOrderedReturn(false)
                .enableReturnOfDeletedEntities(true).withReadOnly(true).withBatchSize(8).multiLoad(ids);
    }
    Examples synchronize(Session s, String key, boolean enabled) {
        return s.bySimpleNaturalId(Examples.class).setSynchronizationEnabled(enabled).load(key);
    }
    Examples parenthesized(Session s, Long id) { return (s.byId(Examples.class)).load(id); }
    Examples classVariable(Session s, Class<Examples> type, Long id) { return s.byId(type).load(id); }
    <T> T generic(Session s, Class<T> type, Long id) { return s.<T>byId(type).load(id); }
    Examples nested(Session s, Long id) { return s.byId(Examples.class).loadOptional(id).orElse(null); }
    Examples comment(Session s, Long id) { return s.byId(Examples.class).load(/*key*/ id); }
}
