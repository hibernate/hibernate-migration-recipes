package fixture.embeddableaccess;

import java.util.*;
import jakarta.persistence.*;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.mapping.Component;

/// Distinguishes field/property state in source and corrected-target metadata and round trips.
/// @author Steve Ebersole
public class RuntimeAccess {
    @Entity @Access(AccessType.PROPERTY)
    public static class IdentifierOwner {
        @Id @Access(AccessType.FIELD) public long id;
        private List<FieldDetail> details = new ArrayList<>();
        @ElementCollection public List<FieldDetail> getDetails() { return details; }
        public void setDetails(List<FieldDetail> value) { details = value; }
    }
    @Entity @Access(AccessType.FIELD)
    public static class OverrideOwner {
        @Id public long id;
        @Transient private PropertyDetail detail;
        @Transient private List<PropertyDetail> details = new ArrayList<>();
        @Transient private Map<PropertyKey,String> entries = new HashMap<>();
        @Embedded @Access(AccessType.PROPERTY) public PropertyDetail getDetail() { return detail; }
        public void setDetail(PropertyDetail value) { detail = value; }
        @ElementCollection @Access(AccessType.PROPERTY) public List<PropertyDetail> getDetails() { return details; }
        public void setDetails(List<PropertyDetail> value) { details = value; }
        @ElementCollection @Access(AccessType.PROPERTY) public Map<PropertyKey,String> getEntries() { return entries; }
        public void setEntries(Map<PropertyKey,String> value) { entries = value; }
    }
    @Embeddable public static class FieldDetail {
        public String fieldText;
        @Transient private String propertyState;
        @Embedded public Nested nested;
        public String getPropertyText() { return propertyState; }
        public void setPropertyText(String value) { propertyState = value; }
        public Nested getNested() { return nested; }
        public void setNested(Nested value) { nested = value; }
    }
    @Embeddable public static class Nested {
        public String nestedText;
        public String getNestedText() { return nestedText; }
        public void setNestedText(String value) { nestedText = value; }
    }
    @Embeddable public static class PropertyDetail {
        public String fieldText;
        @Transient private String propertyState;
        public String getPropertyText() { return propertyState; }
        public void setPropertyText(String value) { propertyState = value; }
    }
    @Embeddable public static class PropertyKey {
        public String fieldKey;
        @Transient private String propertyState;
        public String getPropertyKey() { return propertyState; }
        public void setPropertyKey(String value) { propertyState = value; }
        @Override public boolean equals(Object other) { return other instanceof PropertyKey k && Objects.equals(propertyState, k.propertyState); }
        @Override public int hashCode() { return Objects.hashCode(propertyState); }
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("probe")) {
            try { Class.forName("org.hibernate.boot.model.internal.CollectionBinder").getDeclaredMethod("embeddableAccessType"); }
            catch (NoSuchMethodException e) { System.exit(77); }
            return;
        }
        boolean baseline = args[0].equals("baseline");
        var registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.url", "jdbc:h2:mem:access_" + args[0] + ";DB_CLOSE_DELAY=-1")
                .applySetting("hibernate.connection.driver_class", "org.h2.Driver")
                .applySetting("hibernate.hbm2ddl.auto", "create-drop").build();
        try {
            var metadata = new MetadataSources(registry).addAnnotatedClass(IdentifierOwner.class).addAnnotatedClass(OverrideOwner.class).buildMetadata();
            var collection = (org.hibernate.mapping.Collection) metadata.getEntityBinding(IdentifierOwner.class.getName()).getProperty("details").getValue();
            Component field = (Component) collection.getElement();
            Component property = (Component) metadata.getEntityBinding(OverrideOwner.class.getName()).getProperty("detail").getValue();
            if (baseline) {
                check(field.getProperty("propertyText").getPropertyAccessorName().equals("property"), "uncorrected identifier fallback");
                check(property.getProperty("fieldText").getPropertyAccessorName().equals("field"), "uncorrected embedding override");
                System.out.println("BASELINE_DIFFERENCE_VERIFIED"); return;
            }
            check(field.getProperty("fieldText").getPropertyAccessorName().equals("field"), "identifier fallback must remain FIELD");
            check(((Component) field.getProperty("nested").getValue()).getProperty("nestedText").getPropertyAccessorName().equals("field"), "nested access");
            check(property.getProperty("propertyText").getPropertyAccessorName().equals("property"), "embedding override must remain PROPERTY");
            var map = (org.hibernate.mapping.Map) metadata.getEntityBinding(OverrideOwner.class.getName()).getProperty("entries").getValue();
            check(((Component) map.getIndex()).getProperty("propertyKey").getPropertyAccessorName().equals("property"), "map key access");
            try (var factory = metadata.buildSessionFactory(); var session = factory.openSession()) {
                var tx = session.beginTransaction();
                IdentifierOwner a = new IdentifierOwner(); a.id=1;
                FieldDetail fieldValue = new FieldDetail(); fieldValue.fieldText="field state"; fieldValue.setPropertyText("wrong property state");
                fieldValue.nested=new Nested(); fieldValue.nested.nestedText="nested state"; a.details.add(fieldValue);
                OverrideOwner b = new OverrideOwner(); b.id=2;
                PropertyDetail propertyValue = new PropertyDetail(); propertyValue.fieldText="wrong field state"; propertyValue.setPropertyText("property state");
                b.detail=propertyValue; b.details.add(propertyValue);
                PropertyKey key = new PropertyKey(); key.fieldKey="wrong key state"; key.setPropertyKey("property key"); b.entries.put(key,"map value");
                session.persist(a); session.persist(b); session.flush(); session.clear();
                IdentifierOwner loadedA = session.find(IdentifierOwner.class,1L);
                check(loadedA.details.get(0).fieldText.equals("field state"), "field round trip");
                check(loadedA.details.get(0).nested.nestedText.equals("nested state"), "nested round trip");
                OverrideOwner loadedB = session.find(OverrideOwner.class,2L);
                check(loadedB.detail.getPropertyText().equals("property state"), "singular property round trip");
                check(loadedB.details.get(0).getPropertyText().equals("property state"), "collection property round trip");
                check(loadedB.entries.keySet().iterator().next().getPropertyKey().equals("property key"), "map key round trip");
                tx.rollback();
            }
            System.out.println("ACCESS_PRESERVED");
        }
        finally { StandardServiceRegistryBuilder.destroy(registry); }
    }
    private static void check(boolean success, String message) { if (!success) throw new AssertionError(message); }
}
