package fixture.temporal.temporalcallerconversion.crossfilewritesareconvertedandreadsblock;

class Writer {
	void write(Example e, java.util.Date d) {
		e.value = d;
	}
}