package fixture.jpa4.namedqueryconversion.mixedcontainersandoriginalreportlocations;

import jakarta.persistence.*;

@NamedQueries(value = { /*first*/
		@NamedQuery(name = "update", query = "update Thing set n = 1"), /*between*/
		@NamedQuery(name = "select", query = "select t from Thing t"),
		@NamedQuery(name = "constant", query = Example.QUERY) /*end*/
})
@NamedNativeQueries({ @NamedNativeQuery(name = "native", query = "with c as (select 1) delete from thing") })
/*later*/ class Example {
	static final String QUERY = "delete from Thing";
	// unrelated
	String value = "hello";
}
