package fixture.jpa4.namedqueryconversion.namedquerytypeparameterconflicts;

import jakarta.persistence.NamedQuery;
import jakarta.persistence.NamedNativeQuery;

@NamedQuery(name = "update", query = "update Thing set id=1")
class Example<NamedStatement> {
}

@NamedNativeQuery(name = "nativeUpdate", query = "update thing set id=1")
class NativeExample<NamedNativeStatement> {
}
