package net.suteren.jdbc.influxdb.statement;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.logging.Logger;

import org.influxdb.InfluxDB;

import net.suteren.jdbc.influxdb.InfluxDbConnection;
import net.suteren.jdbc.influxdb.resultset.InfluxDbResultSet;

// Patched copy of net.suteren.jdbc.influxdb:influxdb-jdbc:0.2.6's
// AbstractInfluxDbStatement (Apache-2.0), recompiled into the same class file inside
// the bundled driver jar - see pom.xml's patch-influxdb-driver execution. Upstream's
// close() unconditionally calls getResultSet().close(), which is null whenever
// executeQuery() threw before assigning the resultSet field (a failed query, a
// request timeout, ...). SQuirreL's ContentsTab always closes the statement in a
// finally block, so that NPE silently replaces and hides the real underlying
// SQLException from the user - the only functional change here is the null check
// that lets the real error surface instead. Lombok's @Getter/@Setter are expanded by
// hand below so this file compiles with plain javac, without pulling in Lombok as a
// build-time dependency just for this one patched file.
public abstract class AbstractInfluxDbStatement implements Statement {
	protected final InfluxDbConnection influxDbConnection;
	protected final InfluxDB client;
	protected final Logger log;
	protected SQLWarning error;
	protected InfluxDbResultSet resultSet;
	boolean escapeProcessing;
	private int fetchDirection;
	private int fetchSize;
	private boolean closed = false;
	private int queryTimeout;
	private int maxRows;
	private int maxFieldSize;
	private boolean poolable;
	private boolean closeOnCompletion;
	private final int resultSetHoldability = ResultSet.HOLD_CURSORS_OVER_COMMIT;
	private final int resultSetType = ResultSet.TYPE_SCROLL_INSENSITIVE;
	private final int resultSetConcurrency = ResultSet.CONCUR_READ_ONLY;

	protected AbstractInfluxDbStatement(InfluxDbConnection influxDbConnection, InfluxDB client) {
		this.influxDbConnection = influxDbConnection;
		this.client = client;
		log = influxDbConnection.getMetaData().getDriver().getParentLogger();
	}

	@Override public void close() {
		if (resultSet != null) {
			resultSet.close();
		}
		closed = true;
	}

	@Override public void cancel() {

	}

	@Override public SQLWarning getWarnings() {
		return new SQLWarning(error);
	}

	@Override public void clearWarnings() {
		error = null;
	}

	@Override public void setCursorName(String name) {
		getResultSet().setCursorName(name);
	}

	@Override public InfluxDbResultSet getResultSet() {
		return resultSet;
	}

	@Override public int getUpdateCount() {
		return -1;
	}

	@Override public boolean getMoreResults() {
		boolean moreResults = getResultSet().getMoreResults();
		log.fine(() -> String.format("getMoreResults() is %s", moreResults));
		return moreResults;
	}

	@Override public InfluxDbConnection getConnection() {
		return influxDbConnection;
	}

	@Override public boolean getMoreResults(int current) {
		return getMoreResults();
	}

	@Override public ResultSet getGeneratedKeys() {
		return null;
	}

	@Override public void closeOnCompletion() {
		closeOnCompletion = true;
	}

	@Override public <T> T unwrap(Class<T> iface) {
		return null;
	}

	@Override public boolean isWrapperFor(Class<?> iface) {
		return false;
	}

	@Override public int executeUpdate(String sql, String[] columnNames) throws SQLException {
		return executeUpdate(sql, getColumnIndexes(columnNames));
	}

	@Override public boolean execute(String sql, String[] columnNames) throws SQLException {
		return execute(sql, getColumnIndexes(columnNames));
	}

	private int[] getColumnIndexes(String[] columnNames) throws SQLException {
		int[] columnIndexes = new int[columnNames.length];
		for (int i = 0; i < columnNames.length; i++) {
			columnIndexes[i] = resultSet.findColumn(columnNames[i]);
		}
		return columnIndexes;
	}

	public void setEscapeProcessing(boolean escapeProcessing) {
		this.escapeProcessing = escapeProcessing;
	}

	@Override public int getFetchDirection() {
		return fetchDirection;
	}

	@Override public void setFetchDirection(int fetchDirection) {
		this.fetchDirection = fetchDirection;
	}

	@Override public int getFetchSize() {
		return fetchSize;
	}

	@Override public void setFetchSize(int fetchSize) {
		this.fetchSize = fetchSize;
	}

	@Override public boolean isClosed() {
		return closed;
	}

	@Override public int getQueryTimeout() {
		return queryTimeout;
	}

	@Override public void setQueryTimeout(int queryTimeout) {
		this.queryTimeout = queryTimeout;
	}

	@Override public int getMaxRows() {
		return maxRows;
	}

	@Override public void setMaxRows(int maxRows) {
		this.maxRows = maxRows;
	}

	@Override public int getMaxFieldSize() {
		return maxFieldSize;
	}

	@Override public void setMaxFieldSize(int maxFieldSize) {
		this.maxFieldSize = maxFieldSize;
	}

	@Override public boolean isPoolable() {
		return poolable;
	}

	@Override public void setPoolable(boolean poolable) {
		this.poolable = poolable;
	}

	@Override public boolean isCloseOnCompletion() {
		return closeOnCompletion;
	}

	@Override public int getResultSetHoldability() {
		return resultSetHoldability;
	}

	@Override public int getResultSetType() {
		return resultSetType;
	}

	@Override public int getResultSetConcurrency() {
		return resultSetConcurrency;
	}
}
