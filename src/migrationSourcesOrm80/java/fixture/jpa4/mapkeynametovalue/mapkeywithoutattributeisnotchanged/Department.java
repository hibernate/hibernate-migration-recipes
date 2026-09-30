package fixture.jpa4.mapkeynametovalue.mapkeywithoutattributeisnotchanged;

import jakarta.persistence.MapKey;

import java.util.Map;

class Department {
	@MapKey
	Map<Integer, Object> employees;
}
