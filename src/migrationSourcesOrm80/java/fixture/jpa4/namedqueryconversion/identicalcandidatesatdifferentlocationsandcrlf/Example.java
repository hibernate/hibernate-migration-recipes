package fixture.jpa4.namedqueryconversion.identicalcandidatesatdifferentlocationsandcrlf;

import jakarta.persistence.*;

@NamedQuery(name = "same", query = "with unsupported")
class A {
}

@NamedQuery(name = "same", query = "with unsupported")
class B {
}
