package fixture.temporal.temporalsafety.callerimportblockspropertyatomically;

import fixture.temporal.temporalsafety.callerimportblockspropertyatomically.p.java;
import java.util.Date;

class Client {
	void use(Entity e) {
		e.setCreated(new Date());
	}
}
