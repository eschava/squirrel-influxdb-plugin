package net.suteren.jdbc.influxdb.statement;

import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.influxdb.InfluxDB;
import org.influxdb.dto.Query;
import org.influxdb.dto.QueryResult;

import net.suteren.jdbc.influxdb.InfluxDbConnection;
import net.suteren.jdbc.influxdb.resultset.InfluxDbResultSet;

// Patched copy of net.suteren.jdbc.influxdb:influxdb-jdbc:0.2.6's InfluxDbStatement
// (Apache-2.0) - see pom.xml's patch-influxdb-driver execution and executeCommand()
// below for the actual change.
public class InfluxDbStatement extends AbstractInfluxDbStatement {
	public InfluxDbStatement(InfluxDbConnection influxDbConnection, InfluxDB client) {
		super(influxDbConnection, client);
	}

	public InfluxDbResultSet executeQuery(String sql) throws SQLException {
		try {
			this.resultSet = new InfluxDbResultSet(this, this.executeCommand(sql).getResults());
			return this.resultSet;
		} catch (Exception var3) {
			throw new SQLException(String.format("Execution of query '%s' failed: %s", sql, var3.getMessage()), var3);
		}
	}

	private QueryResult executeCommand(String sql) {
		this.log.fine(() -> String.format("Executing query %s", sql));
		// nativeSQL() strips the database segment off a qualified DELETE (InfluxQL's DELETE
		// rejects it) - recover it from the original sql *before* that happens, so the
		// database can still be applied to the request explicitly below. See
		// InfluxDbConnection.extractDeleteDatabase() for why this is necessary: without it,
		// a stripped DELETE silently targets whichever database this connection has open by
		// default, not the table's actual database.
		String deleteDatabase = this.getConnection().extractDeleteDatabase(sql);
		String command = this.getConnection().nativeSQL(sql);
		// Upstream stores setMaxRows()'s value (see AbstractInfluxDbStatement.maxRows) but
		// never reads it back anywhere - SQuirreL's "Limit rows" setting (Session
		// Properties > Object Tree > "Contents - Limit rows", also the SQL tab's own
		// "Limit Rows" field) calls Statement.setMaxRows() before running a query exactly
		// the way any standard JDBC driver would enforce it, and this driver silently
		// ignored it - every Content tab read the *entire* table (seen: 438,672 rows on a
		// table with a 100-row limit configured). InfluxQL has no server-side equivalent
		// of setMaxRows, so translate it into an explicit LIMIT clause instead. Only for
		// plain SELECTs without an existing LIMIT - SHOW ... metadata queries (used by
		// GetColumnResultSet/GetTablesResultSet) don't take LIMIT the same way and must be
		// left untouched, and a query that already specifies its own LIMIT should win.
		int maxRows = getMaxRows();
		if (maxRows > 0 && isUnlimitedSelect(command)) {
			command = command.trim() + " LIMIT " + maxRows;
		}
		String nativeCommand = command;
		this.log.fine(() -> String.format("Executing NATIVE query %s", nativeCommand));
		// Explicitly target the database recovered above when this was a qualified DELETE,
		// overriding this connection's default database for just this one query. Every other
		// statement shape keeps relying on the connection's default the way it always did
		// (deleteDatabase is null for anything that isn't a qualified DELETE).
		Query influxQuery = deleteDatabase != null ? new Query(nativeCommand, deleteDatabase) : new Query(nativeCommand);
		QueryResult query = this.client.query(influxQuery);
		// QueryResult.getError() only ever reflects a *top-level* error - InfluxDB puts
		// per-statement failures (e.g. "insufficient permissions" on a DELETE the
		// connected user isn't allowed to run) inside each individual Result's own error
		// field instead, which upstream never looked at. That meant every such failure
		// was completely silent: no exception, no warning even, just an empty result -
		// e.g. SQuirreL's row delete feature would report "success" (0 rows affected,
		// same as "already gone") for a delete that was actually rejected outright.
		// Surface the first one as a real, thrown error instead - exactly like a parse
		// error (caught earlier, by the underlying HTTP client itself) already does.
		String resultError = firstResultError(query);
		if (resultError != null) {
			throw new RuntimeException(resultError);
		}
		this.error = new SQLWarning(query.getError());
		if (isUngroupedCountQuery(nativeCommand)) {
			dropLeadingTimeColumn(query);
		}
		return query;
	}

	private static String firstResultError(QueryResult query) {
		if (query.getResults() == null) {
			return null;
		}
		for (QueryResult.Result result : query.getResults()) {
			if (result.getError() != null) {
				return result.getError();
			}
		}
		return null;
	}

	private static boolean isUnlimitedSelect(String sql) {
		String trimmed = sql.trim();
		String upper = trimmed.toUpperCase(Locale.ROOT);
		return upper.startsWith("SELECT") && !upper.contains(" LIMIT ") && !upper.endsWith(" LIMIT");
	}

	// InfluxQL always answers an aggregate query (COUNT, SUM, MEAN, ...) with "time" as
	// its first column - normally the start of each GROUP BY time() bucket, or a single
	// epoch-0 placeholder row when there's no such grouping. SQuirreL's row delete/update
	// safety check (DataSetUpdateableTableModelImpl.count()) always runs a plain
	// "select count(*) from <table> <where clause>" with no GROUP BY and reads the
	// resulting count with rs.getInt(1), assuming - like any ordinary SQL database -
	// that column 1 *is* the count. Here it's "time" (e.g. "1970-01-01T00:00:00Z"),
	// which fails to parse as an int and turns "does this WHERE clause match exactly one
	// row?" into a hard error before any delete/update can even be attempted. Since a
	// GROUP BY time() query is the one case where that leading time column is actually
	// meaningful (one row per bucket), this only strips it for the ungrouped case.
	private static boolean isUngroupedCountQuery(String sql) {
		String upper = sql.trim().toUpperCase(Locale.ROOT);
		return upper.startsWith("SELECT COUNT(") && !upper.contains("GROUP BY");
	}

	private static void dropLeadingTimeColumn(QueryResult query) {
		if (query.getResults() == null) {
			return;
		}
		for (QueryResult.Result result : query.getResults()) {
			if (result.getSeries() == null) {
				continue;
			}
			for (QueryResult.Series series : result.getSeries()) {
				List<String> columns = series.getColumns();
				if (columns == null || columns.isEmpty() || !"time".equalsIgnoreCase(columns.get(0))) {
					continue;
				}
				series.setColumns(columns.subList(1, columns.size()));
				if (series.getValues() != null) {
					List<List<Object>> newValues = new ArrayList<>();
					for (List<Object> row : series.getValues()) {
						newValues.add(row.subList(1, row.size()));
					}
					series.setValues(newValues);
				}
			}
		}
	}

	public int executeUpdate(String sql) throws SQLException {
		try {
			InfluxDbResultSet r = this.executeQuery(sql);

			int count;
			try {
				count = r.getCurrentRows().size();
			} catch (Throwable t) {
				if (r != null) {
					try {
						r.close();
					} catch (Throwable t2) {
						t.addSuppressed(t2);
					}
				}
				throw t;
			}

			if (r != null) {
				r.close();
			}

			return count;
		} catch (SQLException e) {
			throw e;
		} catch (Exception e) {
			throw new SQLException(String.format("Execution of query '%s' failed: %s", sql, e.getMessage()), e);
		}
	}

	public boolean execute(String sql) throws SQLException {
		return this.executeUpdate(sql) > 0;
	}

	public void addBatch(String sql) {
		throw new UnsupportedOperationException();
	}

	public void clearBatch() {
		throw new UnsupportedOperationException();
	}

	public int[] executeBatch() {
		return new int[0];
	}

	public int executeUpdate(String sql, int autoGeneratedKeys) {
		return 0;
	}

	public int executeUpdate(String sql, int[] columnIndexes) {
		return 0;
	}

	public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
		return this.execute(sql);
	}

	public boolean execute(String sql, int[] columnIndexes) throws SQLException {
		return this.execute(sql);
	}
}
