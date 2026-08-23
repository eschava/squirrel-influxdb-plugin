package net.suteren.jdbc.influxdb.statement;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Calendar;
import java.util.TreeMap;

import org.influxdb.InfluxDB;

import net.suteren.jdbc.influxdb.InfluxDbConnection;

// Patched copy of net.suteren.jdbc.influxdb:influxdb-jdbc:0.2.6's
// InfluxDbPreparedStatement (Apache-2.0) - see pom.xml's patch-influxdb-driver
// execution. Upstream left every setXxx() parameter setter throwing
// UnsupportedOperationException and executeQuery()/executeUpdate()/execute() as
// no-ops - this class never actually supported bound parameters at all. SQuirreL's
// row delete/update feature relies on exactly this: it builds a "SELECT count(*) ...
// WHERE col1=? AND col2=? ..." safety check and the real "DELETE ... WHERE ..." as
// PreparedStatements with bound values, so every attempt to delete a row from the
// Content tab failed immediately in the safety check with UnsupportedOperationException
// (see DataSetUpdateableTableModelImpl.count() in SQuirreL's own core). InfluxQL has
// no server-side parameter binding, so this substitutes each bound value into the SQL
// text (formatted as an InfluxQL literal) in place of its "?" before delegating to the
// same executeQuery(String)/executeUpdate(String)/execute(String) any other statement
// uses.
public class InfluxDbPreparedStatement extends InfluxDbStatement implements PreparedStatement {
	private final String sql;
	private final TreeMap<Integer, String> parameters = new TreeMap<>();

	public InfluxDbPreparedStatement(InfluxDbConnection influxDbConnection, String sql, InfluxDB influxDbClient) {
		super(influxDbConnection, influxDbClient);
		this.sql = sql;
	}

	private String substitute() throws SQLException {
		StringBuilder result = new StringBuilder();
		int paramIndex = 0;
		for (int i = 0; i < sql.length(); i++) {
			char c = sql.charAt(i);
			if (c == '?') {
				paramIndex++;
				String literal = parameters.get(paramIndex);
				if (literal == null) {
					throw new SQLException("No value set for parameter " + paramIndex + " in: " + sql);
				}
				result.append(literal);
			} else {
				result.append(c);
			}
		}
		return result.toString();
	}

	public ResultSet executeQuery() throws SQLException {
		return executeQuery(substitute());
	}

	public int executeUpdate() throws SQLException {
		return executeUpdate(substitute());
	}

	public boolean execute() throws SQLException {
		return execute(substitute());
	}

	public void clearParameters() {
		parameters.clear();
	}

	private void setParam(int parameterIndex, String literal) {
		parameters.put(parameterIndex, literal);
	}

	private static String quoteString(String x) {
		return "'" + x.replace("\\", "\\\\").replace("'", "\\'") + "'";
	}

	private static String formatTimestamp(java.util.Date x) {
		return "'" + Instant.ofEpochMilli(x.getTime()) + "'";
	}

	public void setNull(int parameterIndex, int sqlType) {
		setParam(parameterIndex, "NULL");
	}

	public void setNull(int parameterIndex, int sqlType, String typeName) {
		setParam(parameterIndex, "NULL");
	}

	public void setBoolean(int parameterIndex, boolean x) {
		setParam(parameterIndex, Boolean.toString(x));
	}

	public void setByte(int parameterIndex, byte x) {
		setParam(parameterIndex, Byte.toString(x));
	}

	public void setShort(int parameterIndex, short x) {
		setParam(parameterIndex, Short.toString(x));
	}

	public void setInt(int parameterIndex, int x) {
		setParam(parameterIndex, Integer.toString(x));
	}

	public void setLong(int parameterIndex, long x) {
		setParam(parameterIndex, Long.toString(x));
	}

	public void setFloat(int parameterIndex, float x) {
		setParam(parameterIndex, Float.toString(x));
	}

	public void setDouble(int parameterIndex, double x) {
		setParam(parameterIndex, Double.toString(x));
	}

	public void setBigDecimal(int parameterIndex, BigDecimal x) {
		setParam(parameterIndex, x == null ? "NULL" : x.toPlainString());
	}

	public void setString(int parameterIndex, String x) {
		setParam(parameterIndex, x == null ? "NULL" : quoteString(x));
	}

	public void setBytes(int parameterIndex, byte[] x) {
		throw new UnsupportedOperationException();
	}

	public void setDate(int parameterIndex, Date x) {
		setParam(parameterIndex, x == null ? "NULL" : formatTimestamp(x));
	}

	public void setTime(int parameterIndex, Time x) {
		setParam(parameterIndex, x == null ? "NULL" : formatTimestamp(x));
	}

	public void setTimestamp(int parameterIndex, Timestamp x) {
		setParam(parameterIndex, x == null ? "NULL" : formatTimestamp(x));
	}

	public void setAsciiStream(int parameterIndex, InputStream x, int length) {
		throw new UnsupportedOperationException();
	}

	public void setUnicodeStream(int parameterIndex, InputStream x, int length) {
		throw new UnsupportedOperationException();
	}

	public void setBinaryStream(int parameterIndex, InputStream x, int length) {
		throw new UnsupportedOperationException();
	}

	public void setObject(int parameterIndex, Object x, int targetSqlType) {
		setObject(parameterIndex, x);
	}

	public void setObject(int parameterIndex, Object x) {
		if (x == null) {
			setParam(parameterIndex, "NULL");
		} else if (x instanceof String s) {
			setString(parameterIndex, s);
		} else if (x instanceof Boolean b) {
			setBoolean(parameterIndex, b);
		} else if (x instanceof java.util.Date d) {
			setParam(parameterIndex, formatTimestamp(d));
		} else if (x instanceof BigDecimal d) {
			setBigDecimal(parameterIndex, d);
		} else if (x instanceof Number n) {
			setParam(parameterIndex, n.toString());
		} else {
			setString(parameterIndex, x.toString());
		}
	}

	public void addBatch() {
		throw new UnsupportedOperationException();
	}

	public void setCharacterStream(int parameterIndex, Reader reader, int length) {
		throw new UnsupportedOperationException();
	}

	public void setRef(int parameterIndex, Ref x) {
		throw new UnsupportedOperationException();
	}

	public void setBlob(int parameterIndex, Blob x) {
		throw new UnsupportedOperationException();
	}

	public void setClob(int parameterIndex, Clob x) {
		throw new UnsupportedOperationException();
	}

	public void setArray(int parameterIndex, Array x) {
		throw new UnsupportedOperationException();
	}

	public ResultSetMetaData getMetaData() {
		return null;
	}

	public void setDate(int parameterIndex, Date x, Calendar cal) {
		setDate(parameterIndex, x);
	}

	public void setTime(int parameterIndex, Time x, Calendar cal) {
		setTime(parameterIndex, x);
	}

	public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) {
		setTimestamp(parameterIndex, x);
	}

	public void setURL(int parameterIndex, URL x) {
		throw new UnsupportedOperationException();
	}

	public ParameterMetaData getParameterMetaData() {
		return null;
	}

	public void setRowId(int parameterIndex, RowId x) {
		throw new UnsupportedOperationException();
	}

	public void setNString(int parameterIndex, String value) {
		setString(parameterIndex, value);
	}

	public void setNCharacterStream(int parameterIndex, Reader value, long length) {
		throw new UnsupportedOperationException();
	}

	public void setNClob(int parameterIndex, NClob value) {
		throw new UnsupportedOperationException();
	}

	public void setClob(int parameterIndex, Reader reader, long length) {
		throw new UnsupportedOperationException();
	}

	public void setBlob(int parameterIndex, InputStream inputStream, long length) {
		throw new UnsupportedOperationException();
	}

	public void setNClob(int parameterIndex, Reader reader, long length) {
		throw new UnsupportedOperationException();
	}

	public void setSQLXML(int parameterIndex, SQLXML xmlObject) {
		throw new UnsupportedOperationException();
	}

	public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) {
		setObject(parameterIndex, x);
	}

	public void setAsciiStream(int parameterIndex, InputStream x, long length) {
		throw new UnsupportedOperationException();
	}

	public void setBinaryStream(int parameterIndex, InputStream x, long length) {
		throw new UnsupportedOperationException();
	}

	public void setCharacterStream(int parameterIndex, Reader reader, long length) {
		throw new UnsupportedOperationException();
	}

	public void setAsciiStream(int parameterIndex, InputStream x) {
		throw new UnsupportedOperationException();
	}

	public void setBinaryStream(int parameterIndex, InputStream x) {
		throw new UnsupportedOperationException();
	}

	public void setCharacterStream(int parameterIndex, Reader reader) {
		throw new UnsupportedOperationException();
	}

	public void setNCharacterStream(int parameterIndex, Reader value) {
		throw new UnsupportedOperationException();
	}

	public void setClob(int parameterIndex, Reader reader) {
		throw new UnsupportedOperationException();
	}

	public void setBlob(int parameterIndex, InputStream inputStream) {
		throw new UnsupportedOperationException();
	}

	public void setNClob(int parameterIndex, Reader reader) {
		throw new UnsupportedOperationException();
	}
}
