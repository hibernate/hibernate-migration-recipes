package fixture.jpa4.mapkeyconversion.mapkeys;

import jakarta.persistence.*;

import java.util.Map;

class Example {
	static final String KEY = "id";
	@MapKey(/*before*/ name /*equal*/ = /*value*/ KEY /*after*/)
	Map<String, Object> values;
	@jakarta.persistence.MapKey(name = "")
	Map<String, Object> empty;

	@MapKey(name = "id")
	Map<String, Object> getValues() {
		return values;
	}

	@MapKey
	Map<String, Object> plain;
	String unrelated = "name";
}
