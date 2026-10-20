package fixture.loadaccess;

import java.util.Map;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.EntityNotFoundException;
import org.hibernate.Hibernate;
import org.hibernate.KeyType;
import org.hibernate.Session;
import org.hibernate.annotations.NaturalId;
import org.hibernate.cfg.Configuration;

/// Demonstrates why Beta2 reference replacements must remain manual migrations.
/// @author Steve Ebersole
public class ReferenceContracts {
    @Entity(name = "ReferenceItem")
    public static class Item {
        @Id public Long id;
        @NaturalId(mutable = true) public String code;
        public Item() {}
    }
    @Entity(name = "EagerReferenceItem")
    public static final class EagerItem {
        @Id public Long id;
        public String label;
        public EagerItem() {}
    }
    public static Map<String, Object> verify() {
        try (var factory = new Configuration().addAnnotatedClass(Item.class).addAnnotatedClass(EagerItem.class)
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:reference_contracts;DB_CLOSE_DELAY=-1")
                .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory()) {
            try (var s = factory.openSession()) {
                var tx = s.beginTransaction();
                Item item = new Item(); item.id = 1L; item.code = "old"; s.persist(item);
                EagerItem eager = new EagerItem(); eager.id = 2L; eager.label = "eager"; s.persist(eager); tx.commit();
            }
            try (var s = factory.openSession()) {
                s.setDefaultReadOnly(true);
                Item legacy = s.byId(Item.class).getReference(1L);
                check(!Hibernate.isInitialized(legacy), "legacy reference stays lazy");
                check(s.isReadOnly(legacy), "legacy reference inherits read-only");
                Hibernate.initialize(legacy);
                check(s.isReadOnly(legacy), "legacy initialized reference remains read-only");
                s.clear();
                Item modern = modern(s, 1L, KeyType.IDENTIFIER);
                check(!Hibernate.isInitialized(modern), "modern reference stays lazy");
                check(s.isReadOnly(modern), "lazy proxy still inherits session read-only");
                EagerItem legacyEager = s.byId(EagerItem.class).getReference(2L);
                check(s.isReadOnly(legacyEager), "eager legacy reference inherits read-only");
                s.clear();
                EagerItem modernEager = (EagerItem) modern(s, EagerItem.class, 2L, KeyType.IDENTIFIER);
                check(!s.isReadOnly(modernEager), "eager modern reference forces read-write");
                check(s.bySimpleNaturalId(Item.class).getReference("missing") == null, "legacy missing natural reference");
                boolean threw = false;
                try { modern(s, "missing", KeyType.NATURAL); }
                catch (EntityNotFoundException expected) { threw = true; }
                check(threw, "modern missing natural reference throws");
            }
            try (var s = factory.openSession()) {
                var tx = s.beginTransaction();
                Item mutable = s.bySimpleNaturalId(Item.class).load("old");
                mutable.code = "new";
                boolean threw = false;
                try { modern(s, "new", KeyType.NATURAL); }
                catch (EntityNotFoundException expected) { threw = true; }
                check(threw, "modern reference does not synchronize mutable natural ids");
                check(s.bySimpleNaturalId(Item.class).getReference("new") == mutable, "legacy reference synchronizes");
                tx.rollback();
            }
        }
        return Map.of("referenceContracts", true);
    }
    // The replacement exists only in Beta2; reflection keeps this original fixture compilable on 7.4.
    private static Item modern(Session session, Object key, KeyType keyType) {
        return (Item) modern(session, Item.class, key, keyType);
    }
    private static Object modern(Session session, Class<?> entity, Object key, KeyType keyType) {
        try {
            return Session.class.getMethod("getReference", Class.class, Object.class, KeyType.class)
                    .invoke(session, entity, key, keyType);
        }
        catch (java.lang.reflect.InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException cause) throw cause;
            throw new AssertionError(failure.getCause());
        }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
