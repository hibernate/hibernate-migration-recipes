package fixture.jpa4.mapkeynametovalue.mapkeynamebecomesshorthandvalue;

import jakarta.persistence.MapKey;

import java.util.Map;

class Department {
	@MapKey(name = "empId")
	Map<Integer, Object> employees;
}
