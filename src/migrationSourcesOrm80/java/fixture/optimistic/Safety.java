package fixture.optimistic;

import jakarta.persistence.*;
import org.hibernate.annotations.OptimisticLock;
import java.util.List;

/// Mapping ambiguity and imported replacement-name collisions.
/// @author Steve Ebersole
class Safety {
	@interface CustomMapping {}
	@interface ExcludedFromVersioning {}
	static final String OWNER = "";
	@Basic @CustomMapping @OptimisticLock(excluded = false) String custom;
	@ManyToMany(mappedBy = "owner") @OptimisticLock(excluded = false) List<Safety> inverse;
	@ManyToMany(mappedBy = OWNER) @OptimisticLock(excluded = false) List<Safety> expression;
	@OneToOne @JoinColumn(insertable = false) @OptimisticLock(excluded = false) Safety toOne;
	@Embedded @OptimisticLock(excluded = false) Safety embedded;
	@Basic @OneToOne @OptimisticLock(excluded = false) Safety ambiguous;
	@OptimisticLock(excluded = true) String excluded;
	Class<?> legacy = OptimisticLock.class;
	void localClass() {
		class Local {
			@OptimisticLock(excluded = true) String excluded;
		}
	}
}
