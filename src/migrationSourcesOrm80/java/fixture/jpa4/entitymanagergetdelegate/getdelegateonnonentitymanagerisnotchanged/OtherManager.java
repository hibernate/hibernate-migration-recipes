package fixture.jpa4.entitymanagergetdelegate.getdelegateonnonentitymanagerisnotchanged;

class OtherManager {
	Object getDelegate() {
		return this;
	}

	void method(OtherManager other) {
		Object d = other.getDelegate();
	}
}
