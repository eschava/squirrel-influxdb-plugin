package net.suteren.jdbc.influxdb.resultset;

import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.util.List;

import org.influxdb.dto.QueryResult;

// Patched copy of net.suteren.jdbc.influxdb:influxdb-jdbc:0.2.6's
// InfluxDbResultSetMetaData (Apache-2.0) - see pom.xml's patch-influxdb-driver
// execution. Upstream's getColumnType() unconditionally returns 0, which is not a
// valid java.sql.Types code. SQuirreL's CellComponentFactory.getGenericDataType()
// switches on that code and has no branch for 0, silently returning null - which is
// exactly the NPE seen when SQuirreL rebuilds a table's column list from a JTable
// that previously displayed a query result using this metadata (gatherColumnsForContentSelect
// -> CellComponentFactory.getDataTypeObject()). Fixed here by inferring the SQL type
// from the first row's actual value, the same way upstream's own getColumnClassName()
// already does just below.
public class InfluxDbResultSetMetaData implements ResultSetMetaData {
	private final AbstractInfluxDbMultiResultSet influxDbResultSet;

	public InfluxDbResultSetMetaData(AbstractInfluxDbMultiResultSet influxDbResultSet) {
		this.influxDbResultSet = influxDbResultSet;
	}

	@Override public int getColumnCount() {
		return influxDbResultSet.getCurrentSeries()
			.map(QueryResult.Series::getColumns)
			.map(List::size)
			.orElse(0);
	}

	@Override public boolean isAutoIncrement(int column) {
		return false;
	}

	@Override public boolean isCaseSensitive(int column) {
		return true;
	}

	@Override public boolean isSearchable(int column) {
		return false;
	}

	@Override public boolean isCurrency(int column) {
		return false;
	}

	@Override public int isNullable(int column) {
		return ResultSetMetaData.columnNullableUnknown;
	}

	@Override public boolean isSigned(int column) {
		return false;
	}

	@Override public int getColumnDisplaySize(int column) {
		return 0;
	}

	@Override public String getColumnLabel(int column) {
		return getColumnName(column);
	}

	@Override public String getColumnName(int column) {
		return influxDbResultSet.getCurrentSeries()
			.map(QueryResult.Series::getColumns)
			.map(c -> c.get(column - 1))
			.orElse(null);
	}

	@Override public String getSchemaName(int column) {
		return null;
	}

	@Override public int getPrecision(int column) {
		return 0;
	}

	@Override public int getScale(int column) {
		return 0;
	}

	@Override public String getTableName(int column) {
		return influxDbResultSet.getCurrentSeries()
			.map(QueryResult.Series::getName)
			.orElse(null);
	}

	@Override public String getCatalogName(int column) {
		return "";
	}

	@Override public int getColumnType(int column) {
		Object sample = firstRowValue(column);
		if (sample instanceof Boolean) {
			return Types.BOOLEAN;
		} else if (sample instanceof Number) {
			return Types.DOUBLE;
		} else {
			return Types.VARCHAR;
		}
	}

	@Override public String getColumnTypeName(int column) {
		Object sample = firstRowValue(column);
		if (sample instanceof Boolean) {
			return "BOOLEAN";
		} else if (sample instanceof Number) {
			return "DOUBLE";
		} else {
			return "VARCHAR";
		}
	}

	private Object firstRowValue(int column) {
		// getCurrentRows() reflects whichever statement/series this multi-result cursor is
		// currently positioned on (e.g. GetColumnResultSet runs "SHOW FIELD KEYS ...; SHOW TAG
		// KEYS ..." as one query, with 2 columns and 1 column respectively) - it isn't
		// necessarily shaped like the series this particular `column` index was meant for, so
		// this must never assume the row is at least `column` wide.
		List<List<Object>> currentRows = influxDbResultSet.getCurrentRows();
		if (currentRows.isEmpty()) {
			return null;
		}
		List<Object> firstRow = currentRows.get(0);
		int index = column - 1;
		return index >= 0 && index < firstRow.size() ? firstRow.get(index) : null;
	}

	@Override public boolean isReadOnly(int column) {
		return false;
	}

	@Override public boolean isWritable(int column) {
		return false;
	}

	@Override public boolean isDefinitelyWritable(int column) {
		return false;
	}

	@Override public String getColumnClassName(int column) {
		List<List<Object>> currentRows = influxDbResultSet.getCurrentRows();
		if (currentRows.isEmpty()) {
			return null;
		} else {
			Object o = currentRows.get(0).get(column - 1);
			return o == null ? null : o.getClass().getName();
		}
	}

	@Override public <T> T unwrap(Class<T> iface) {
		return null;
	}

	@Override public boolean isWrapperFor(Class<?> iface) {
		return false;
	}
}
