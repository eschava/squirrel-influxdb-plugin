package net.suteren.jdbc.influxdb.resultset.proxy;

import java.sql.SQLException;
import java.util.List;
import java.util.function.IntFunction;

import org.influxdb.dto.QueryResult;

import net.suteren.jdbc.influxdb.InfluxDbConnection;
import net.suteren.jdbc.influxdb.resultset.InfluxDbResultSet;

// New class, not part of upstream net.suteren.jdbc.influxdb:influxdb-jdbc:0.2.6.
// Several InfluxDbMetadata methods (getImportedKeys, getExportedKeys,
// getVersionColumns, getTablePrivileges, getColumnPrivileges, ...) correspond to
// relational concepts InfluxDB genuinely has no equivalent for (foreign keys, row
// versioning, grantable privileges, ...), and upstream just returned null for all of
// them. SQuirreL's per-table info tabs call these directly expecting a ResultSet back
// - even an empty one is fine, but null isn't: ResultSetWrapper.getResultSet()
// returns that null, and the tab's rendering code then NPEs calling .getMetaData() on
// it. This is a real, well-formed ResultSet with zero rows and whatever column names
// the caller needs, so those tabs render as "no rows" - the honest answer for a
// concept InfluxDB doesn't have - instead of crashing.
public class GetEmptyResultSet extends AbstractProxyResultSet {
	public GetEmptyResultSet(InfluxDbConnection influxDbConnection, String[] columns) throws SQLException {
		// AbstractInfluxDbMultiResultSet.getCurrentResult() does results.get(0)
		// unconditionally (inside an Optional.map(), which doesn't catch exceptions) -
		// an empty results list throws ArrayIndexOutOfBoundsException the moment
		// anything touches this ResultSet, e.g. next()'s very first call. A single
		// Result with no series (QueryResult.Result::getSeries returns null) is safe -
		// isAfterLast()/isLast() already treat a null series list as zero series (see
		// AbstractProxyResultSet) - and is what "zero rows, well-formed otherwise"
		// actually looks like to the rest of this driver's result-reading code.
		super(new InfluxDbResultSet(influxDbConnection.createStatement(), List.of(new QueryResult.Result())), columns,
			new Object[columns.length]);
	}

	@Override protected int remapIndex(int columnIndex) {
		return 0;
	}
}
