package fixture;

import jakarta.persistence.*;
import java.util.Date;
import java.util.Calendar;
import java.util.TimeZone;

@Entity(name = "TemporalEntity")
@Access(AccessType.FIELD)
@Table(name = "temporal_entity")
public class TemporalEntity {
	@Id
	public long id;
	@Temporal(TemporalType.DATE)
	public Date localDay = new Date(1710063000000L);
	@Temporal(TemporalType.TIME)
	@Column(name = "local_time_value")
	public Date localTime = new Date(1710063000000L);
	@Temporal(TemporalType.TIMESTAMP)
	public Date stamp = new java.sql.Timestamp(1710063000000L);
	@Temporal(TemporalType.TIMESTAMP)
	public Calendar zoned = calendar();
	@Temporal(TemporalType.TIMESTAMP)
	public Date nullable = null;
	public static int calls;
	public static String evaluationOrder = "";
	@Embedded
	public Details details = new Details();

	public TemporalEntity() {
	}

	public Date getNullable() {
		return nullable;
	}

	public void setNullable(Date input) {
		nullable = input;
	}

	public Calendar getZoned() {
		return zoned;
	}

	public void setZoned(Calendar input) {
		zoned = input;
	}

	TemporalEntity receiver() {
		evaluationOrder += "R";
		return this;
	}

	public void assignThroughAccessor() {
		receiver().setNullable(next());
		Date local = getNullable();
		final Date alias = local;
		if (alias != null)
			setNullable(alias);
	}

	@Embeddable
	@Access(AccessType.PROPERTY)
	public static class Details {
		private Date observed = new Date(1710063000000L);

		@Temporal(TemporalType.TIMESTAMP)
		@Column(name = "observed_value")
		public Date getObserved() {
			return observed;
		}

		public void setObserved(Date input) {
			observed = input;
		}
	}

	public void assign(Date value) {
		nullable = value;
	}

	public void assignOnce() {
		nullable = next();
	}

	static Date next() {
		calls++;
		evaluationOrder += "A";
		return new Date(1710063000000L);
	}

	static Calendar calendar() {
		Calendar result = Calendar.getInstance(TimeZone.getTimeZone("America/Denver"));
		result.setTimeInMillis(1710063000000L);
		return result;
	}
}
