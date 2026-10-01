package fixture.optimistic;

import jakarta.persistence.*;
import org.hibernate.annotations.OptimisticLock;
import java.util.List;

/// Syntax and conservative inclusion cases for optimistic-lock migration.
/// @author Steve Ebersole
public class Examples {
	@Basic
	@OptimisticLock(/*argument*/ excluded /*equal*/ = /*value*/ true /*tail*/)
	String excluded;
	@Basic
	@OptimisticLock(excluded = false)
	String included;
	@ElementCollection
	@OptimisticLock(excluded = false)
	List<String> values;
	@OneToMany
	@OptimisticLock(excluded = false)
	List<Examples> owned;
	@ManyToMany(mappedBy = "")
	@OptimisticLock(excluded = false)
	List<Examples> ownedMany;
	@OneToMany(mappedBy = "owner")
	@OptimisticLock(excluded = false)
	List<Examples> inverse;
	@ManyToOne
	@OptimisticLock(excluded = false)
	Examples owner;
	@OptimisticLock(excluded = false)
	String implicit;
	static final boolean EXCLUDED = true;
	@OptimisticLock(excluded = EXCLUDED)
	String expression;
	@Basic
	@OptimisticLock(excluded = false)
	public String getLabel() {
		return "label";
	}
	@Basic
	@OptimisticLock(excluded = true)
	public String getNotes() {
		return "notes";
	}
	@Basic
	@OptimisticLock(excluded = false)
	String mixed;
	@Basic
	public String getMixed() {
		return mixed;
	}
	@Basic
	@Access(AccessType.FIELD)
	@OptimisticLock(excluded = false)
	String access;
	@Basic
	@AttributeOverride(name = "other", column = @Column(name = "other"))
	@OptimisticLock(excluded = false)
	String override;
}
