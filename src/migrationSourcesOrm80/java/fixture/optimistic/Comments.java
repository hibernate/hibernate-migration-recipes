package fixture.optimistic;

import jakarta.persistence.Basic;
import org.hibernate.annotations.OptimisticLock;

/// Preserves comments on renamed and removed annotations.
/// @author Steve Ebersole
class Comments {
	@Basic
	@/*name*/ OptimisticLock(/*before*/ excluded /*equals*/ = /*literal*/ false /*end*/)
	String included;
	@/*replacementName*/ OptimisticLock(excluded = true)
	String excluded;
	@Basic
	@OptimisticLock(/*getter*/ excluded = false)
	String getLabel() {
		return "label";
	}
}
