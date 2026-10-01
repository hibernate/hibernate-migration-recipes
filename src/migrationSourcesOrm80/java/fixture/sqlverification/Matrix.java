package fixture.sqlverification;

import org.hibernate.annotations.*;

/// SQL verification migration input.
/// @author Steve Ebersole
class Matrix {
	@SQLInsert(sql = "statement", check = ResultCheckStyle.NONE, callable = true)
	static class SQLInsertNONE {}

	@SQLInsert(sql = "statement", check = ResultCheckStyle.COUNT, callable = true)
	static class SQLInsertCOUNT {}

	@SQLInsert(sql = "statement", check = ResultCheckStyle.PARAM, callable = true)
	static class SQLInsertPARAM {}

	@SQLUpdate(sql = "statement", check = ResultCheckStyle.NONE, callable = true)
	static class SQLUpdateNONE {}

	@SQLUpdate(sql = "statement", check = ResultCheckStyle.COUNT, callable = true)
	static class SQLUpdateCOUNT {}

	@SQLUpdate(sql = "statement", check = ResultCheckStyle.PARAM, callable = true)
	static class SQLUpdatePARAM {}

	@SQLDelete(sql = "statement", check = ResultCheckStyle.NONE, callable = true)
	static class SQLDeleteNONE {}

	@SQLDelete(sql = "statement", check = ResultCheckStyle.COUNT, callable = true)
	static class SQLDeleteCOUNT {}

	@SQLDelete(sql = "statement", check = ResultCheckStyle.PARAM, callable = true)
	static class SQLDeletePARAM {}

	@SQLDeleteAll(sql = "statement", check = ResultCheckStyle.NONE, callable = true)
	static class SQLDeleteAllNONE {}

	@SQLDeleteAll(sql = "statement", check = ResultCheckStyle.COUNT, callable = true)
	static class SQLDeleteAllCOUNT {}

	@SQLDeleteAll(sql = "statement", check = ResultCheckStyle.PARAM, callable = true)
	static class SQLDeleteAllPARAM {}

}
