package fixture.sqlverification;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.sql.PreparedStatement;
import org.hibernate.annotations.ResultCheckStyle;
import org.hibernate.annotations.SQLInsert;
import org.hibernate.jdbc.Expectation;

/// Runtime inputs whose custom inserts deliberately affect zero rows.
///
/// @author Steve Ebersole
public class RuntimeEntities {
	@Entity(name = "NoneRecord")
	@Table(name = "none_record")
	@SQLInsert(sql = "insert into none_record (id) select ? where 1=0", check = ResultCheckStyle.NONE)
	public static class NoneRecord {
		@Id public Long id;
	}

	@Entity(name = "CountRecord")
	@Table(name = "count_record")
	@SQLInsert(sql = "insert into count_record (id) select ? where 1=0", check = ResultCheckStyle.COUNT)
	public static class CountRecord {
		@Id public Long id;
	}

	@Entity(name = "CustomRecord")
	@Table(name = "custom_record")
	@SQLInsert(sql = "insert into custom_record (id) select ? where 1=0", check = ResultCheckStyle.COUNT, verify = Custom.class)
	public static class CustomRecord {
		@Id public Long id;
	}

	public static class Custom implements Expectation {
		public static int calls;
		public static int lastRowCount = -1;

		@Override
		public void verifyOutcome(int rowCount, PreparedStatement statement, int batchPosition, String sql) {
			calls++;
			lastRowCount = rowCount;
		}
	}
}
