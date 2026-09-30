package fixture;

import jakarta.persistence.*;
import java.util.*;

public class Migrated {
	public static Object delegate(EntityManager em) {
		return em.getDelegate();
	}

	@Entity(name = "Item")
	@Table(name = "items")
	@NamedQueries(value = {
			@NamedQuery(name = "hqlUpdate", query = "update Item set label = :label where id = :id", hints = @QueryHint(name = "org.hibernate.timeout", value = "5")),
			@NamedQuery(name = "hqlDelete", query = "delete from Item where id = :id"),
			@NamedQuery(name = "hqlInsertValues", query = "insert into Item (id, label) values (:id, :label)"),
			@NamedQuery(name = "hqlInsertSelect", query = "insert into Item (id, label) select :id, label from Item where id = :source"),
			@NamedQuery(name = "hqlSelect", query = "select i from Item i")
	})
	@NamedNativeQueries(value = {
			@NamedNativeQuery(name = "sqlUpdate", query = "update items set label = :label where id = :id"),
			@NamedNativeQuery(name = "sqlDelete", query = "delete from items where id = :id"),
			@NamedNativeQuery(name = "sqlSelect", query = "select * from items", resultClass = Item.class)
	})
	public static class Item {
		@Id
		public Long id;
		public String label;

		public Item() {
		}
	}

	@Entity(name = "Department")
	@Table(name = "departments")
	@NamedNativeQuery(name = "sqlInsert", query = "insert into items (id, label) values (:id, :label)")
	public static class Department {
		@Id
		public Long id;
		@OneToMany(cascade = CascadeType.ALL)
		@JoinColumn(name = "department_id")
		@MapKey(name = "label")
		public Map<String, Item> items = new HashMap<>();

		public Department() {
		}
	}
}
