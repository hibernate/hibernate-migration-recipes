package fixture.loadaccess;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Timeout;
import org.hibernate.CacheMode;
import org.hibernate.LockMode;
import org.hibernate.Session;
import org.hibernate.annotations.NaturalId;
import org.hibernate.annotations.NaturalIdClass;
import org.hibernate.cfg.Configuration;
import org.hibernate.resource.jdbc.spi.StatementInspector;

/// Runs the same assertions against the published source API and actual migrated output.
/// @author Steve Ebersole
public class RuntimeLoads {
    @Entity(name = "LoadItem") @Table(name = "load_item")
    public static class Item {
        @Id public Long id;
        @NaturalId(mutable = true) public String code;
        public String label;
        public Item() {}
        Item(long id, String code) { this.id = id; this.code = code; this.label = code; }
    }
    @Entity(name = "LoadCompound") @Table(name = "load_compound") @NaturalIdClass(CompoundKey.class)
    public static class Compound {
        @Id public Long id;
        @NaturalId public String region;
        @NaturalId public String code;
    }
    public static class CompoundKey {
        public String region;
        public String code;
    }
    public static class Sql implements StatementInspector {
        public final List<String> statements = new ArrayList<>();
        public String inspect(String sql) { statements.add(sql); return sql; }
        long selects() { return statements.stream().filter(s -> s.toLowerCase().startsWith("select")).count(); }
    }

    public static Item id(Session s, Long id) { return s.byId(Item.class).load(id); }
    public static Optional<Item> optional(Session s, Long id) { return s.byId(Item.class).loadOptional(id); }
    public static Item natural(Session s, String code) { return s.bySimpleNaturalId(Item.class).load(code); }
    public static Item named(Session s, String code) { return s.byNaturalId(Item.class).using("code", code).load(); }
    public static Optional<Item> naturalOptional(Session s, String code) { return s.bySimpleNaturalId(Item.class).loadOptional(code); }
    public static Compound compound(Session s, Map<String, Object> keys) { return s.byNaturalId(Compound.class).using(keys).load(); }
    public static List<Item> ids(Session s, List<Long> keys) { return s.byMultipleIds(Item.class).multiLoad(keys); }
    public static List<Item> array(Session s, Long[] keys) { return s.byMultipleIds(Item.class).multiLoad(keys); }
    public static List<Item> naturalIds(Session s, List<String> keys) { return s.byMultipleNaturalId(Item.class).multiLoad(keys); }
    public static List<Item> bulk(Session s, List<Long> keys, boolean checking, boolean ordered, boolean removed) {
        return s.byMultipleIds(Item.class).enableSessionCheck(checking).enableOrderedReturn(ordered)
                .enableReturnOfDeletedEntities(removed).multiLoad(keys);
    }
    public static List<Item> readOnlyBulk(Session s, List<Long> keys, boolean readOnly) {
        return s.byMultipleIds(Item.class).withReadOnly(readOnly).multiLoad(keys);
    }
    public static List<Item> batches(Session s, List<Long> keys) {
        return s.byMultipleIds(Item.class).withBatchSize(1).multiLoad(keys);
    }
    public static Item synchronizedKey(Session s, String code, boolean sync) {
        return s.bySimpleNaturalId(Item.class).setSynchronizationEnabled(sync).load(code);
    }
    public static Item cached(Session s, Long key) { return s.byId(Item.class).with(CacheMode.IGNORE).load(key); }
    public static Item locked(Session s, Long key) {
        return s.byId(Item.class).with(LockMode.PESSIMISTIC_WRITE).with(Timeout.milliseconds(200)).load(key);
    }
    public static Item defaultLock(Session s, Long key) {
        return s.byId(Item.class).with(LockMode.PESSIMISTIC_WRITE).load(key);
    }
    public static Item scopedLock(Session s, Long key) {
        return s.byId(Item.class).with(LockMode.PESSIMISTIC_WRITE, jakarta.persistence.PessimisticLockScope.EXTENDED).load(key);
    }
    public static final List<String> trace = new ArrayList<>();
    private static Session receiver(Session s) { trace.add("receiver"); return s; }
    private static Class<Item> entityType() { trace.add("class"); return Item.class; }
    private static Long key() { trace.add("key"); return 1L; }
    private static boolean flag() { trace.add("option"); return true; }
    public static Item evaluation(Session s) { return receiver(s).byId(entityType()).load(key()); }
    public static Item optionEvaluation(Session s, Long key) { return receiver(s).byId(Item.class).withReadOnly(flag()).load(key); }

    public static Map<String, Object> verify() {
        Sql sql = new Sql();
        Map<String, Object> results = new LinkedHashMap<>();
        try (var factory = new Configuration().addAnnotatedClass(Item.class).addAnnotatedClass(Compound.class)
                .setStatementInspector(sql)
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:load_access;DB_CLOSE_DELAY=-1")
                .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory()) {
            List<org.hibernate.LockOptions> observedLocks = new ArrayList<>();
            factory.unwrap(org.hibernate.engine.spi.SessionFactoryImplementor.class).getServiceRegistry()
                    .getService(org.hibernate.event.service.spi.EventListenerRegistry.class)
                    .prependListeners(org.hibernate.event.spi.EventType.LOAD,
                            (org.hibernate.event.spi.LoadEventListener) (event, type) -> {
                                if (event.getLockOptions() != null) observedLocks.add(event.getLockOptions().makeCopy());
                            });
            try (var s = factory.openSession()) {
                var tx = s.beginTransaction();
                for (int i = 1; i <= 4; i++) s.persist(new Item(i, "k" + i));
                Compound c = new Compound(); c.id = 10L; c.region = "west"; c.code = "compound"; s.persist(c);
                tx.commit();
            }
            try (var s = factory.openSession()) {
                var tx = s.beginTransaction();
                check(id(s, 1L).id == 1L, "identifier found");
                check(id(s, 99L) == null, "identifier missing");
                check(optional(s, 1L).isPresent() && optional(s, 99L).isEmpty(), "optional results");
                check(natural(s, "k2").id == 2L && natural(s, "absent") == null, "natural results");
                check(named(s, "k3").id == 3L, "named natural result");
                check(naturalOptional(s, "k4").isPresent() && naturalOptional(s, "absent").isEmpty(), "natural optional");
                check(compound(s, Map.of("code", "compound", "region", "west")).id == 10L, "compound map with natural-id class");
                s.clear();
                List<Item> found = ids(s, List.of(2L, 99L, 1L, 2L));
                check(found.size() == 4 && found.get(0).id == 2L && found.get(1) == null
                        && found.get(2).id == 1L && found.get(3) == found.get(0), "bulk positions and duplicates");
                check(array(s, new Long[]{1L, 2L}).size() == 2, "varargs array");
                check(ids(s, List.of()).isEmpty(), "empty keys");
                check(naturalIds(s, List.of("k2", "absent", "k1")).get(1) == null, "bulk natural positions");
                s.clear();
                Item managed = id(s, 1L);
                sql.statements.clear();
                check(bulk(s, List.of(1L), true, true, false).get(0) == managed, "managed identity");
                check(sql.selects() == 0, "session check avoids SQL");
                sql.statements.clear();
                ids(s, List.of(1L));
                check(sql.selects() > 0, "legacy default session check disabled");
                s.remove(managed);
                check(bulk(s, List.of(1L), true, true, false).get(0) == null, "removed replacement");
                check(bulk(s, List.of(1L), true, true, true).get(0) == managed, "removed inclusion");
                List<Item> unorderedRemoved = bulk(s, List.of(1L), true, false, false);
                check(unorderedRemoved.stream().allMatch(java.util.Objects::isNull), "unordered removed replacement");
                results.put("unorderedRemovedSize", unorderedRemoved.size());
                tx.rollback();
            }
            for (boolean defaultReadOnly : List.of(false, true)) {
                try (var s = factory.openSession()) {
                    s.setDefaultReadOnly(defaultReadOnly);
                    check(s.isReadOnly(id(s, 1L)) == defaultReadOnly, "single inherits read-only");
                    s.clear();
                    check(!s.isReadOnly(ids(s, List.of(1L)).get(0)), "bulk legacy read-write default");
                    s.clear();
                    check(s.isReadOnly(naturalIds(s, List.of("k1")).get(0)) == defaultReadOnly, "bulk natural read-only default");
                    s.clear();
                    check(s.isReadOnly(readOnlyBulk(s, List.of(1L), true).get(0)), "explicit bulk read-only");
                    s.clear();
                    check(!s.isReadOnly(readOnlyBulk(s, List.of(1L), false).get(0)), "explicit bulk read-write");
                    check(s.isDefaultReadOnly() == defaultReadOnly, "session default unchanged");
                }
            }
            try (var s = factory.openSession()) {
                var tx = s.beginTransaction();
                Item mutable = natural(s, "k1");
                mutable.code = "changed";
                check(synchronizedKey(s, "changed", false) == null, "disabled synchronization");
                check(synchronizedKey(s, "changed", true) == mutable, "enabled synchronization");
                check(synchronizedKey(s, "k1", true) == null, "stale natural key removed");
                tx.rollback();
            }
            try (var s = factory.openSession()) {
                s.setCacheMode(CacheMode.NORMAL);
                check(cached(s, 1L) != null && s.getCacheMode() == CacheMode.NORMAL, "cache mode restored");
                var tx = s.beginTransaction();
                s.setProperty("jakarta.persistence.lock.timeout", 999);
                s.setProperty("jakarta.persistence.lock.scope", jakarta.persistence.PessimisticLockScope.EXTENDED);
                observedLocks.clear();
                check(locked(s, 2L).id == 2L, "pessimistic lock and timeout");
                org.hibernate.LockOptions options = observedLocks.get(observedLocks.size() - 1);
                check(options.getTimeOut() == 200, "explicit timeout retained");
                check(options.getLockScope() == jakarta.persistence.PessimisticLockScope.NORMAL, "legacy default scope");
                check(options.getFollowOnStrategy() == org.hibernate.Locking.FollowOn.ALLOW, "legacy default follow-on");
                observedLocks.clear(); defaultLock(s, 3L);
                options = observedLocks.get(observedLocks.size() - 1);
                check(options.getTimeOut() == -1, "legacy default timeout ignores session hint");
                observedLocks.clear(); scopedLock(s, 4L);
                options = observedLocks.get(observedLocks.size() - 1);
                check(options.getLockScope() == jakarta.persistence.PessimisticLockScope.EXTENDED, "explicit lock scope");
                tx.rollback();
                s.clear();
                sql.statements.clear();
                check(batches(s, List.of(1L, 2L, 3L)).size() == 3, "batch result");
                check(sql.selects() >= 3, "explicit batch size");
                trace.clear(); evaluation(s);
                check(trace.equals(List.of("receiver", "class", "key")), "evaluation order");
                trace.clear(); s.clear(); optionEvaluation(s, 1L);
                check(trace.equals(List.of("receiver", "option")), "option evaluated once");
            }
            results.put("verified", true);
        }
        return results;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
