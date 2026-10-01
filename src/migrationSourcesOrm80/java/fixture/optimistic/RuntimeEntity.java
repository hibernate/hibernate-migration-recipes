package fixture.optimistic;

import jakarta.persistence.*;
import org.hibernate.annotations.OptimisticLock;
import java.util.ArrayList;
import java.util.List;

/// Persistent inputs used to verify version behavior after conversion.
/// @author Steve Ebersole
@Entity
public class RuntimeEntity {
	@Id
	public Long id;
	@Version
	public int version;
	@Basic
	@OptimisticLock(excluded = true)
	public String excluded;
	@Basic
	@OptimisticLock(excluded = false)
	public String included;
	@OneToMany(cascade = CascadeType.ALL)
	@JoinColumn(name = "owner_id")
	@OptimisticLock(excluded = false)
	public List<Child> children = new ArrayList<>();

	@Entity(name = "OptimisticChild")
	public static class Child {
		@Id
		public Long id;
	}
}
