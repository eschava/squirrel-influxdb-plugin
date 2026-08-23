package net.suteren.jdbc.influxdb.resultset.proxy;

import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;

import org.influxdb.dto.QueryResult;

import net.suteren.jdbc.influxdb.InfluxDbConnection;
import net.suteren.jdbc.influxdb.resultset.InfluxDbResultSet;

// Patched copy of net.suteren.jdbc.influxdb:influxdb-jdbc:0.2.6's GetColumnResultSet
// (Apache-2.0) - see pom.xml's patch-influxdb-driver execution and normalizeColumns() below
// for the actual change.
public class GetColumnResultSet extends AbstractProxyResultSet {
	public GetColumnResultSet(InfluxDbConnection influxDbConnection, String tableNamePattern, String catalog)
		throws SQLException {
		super(normalizeColumns(influxDbConnection.createStatement()
				.executeQuery(String.format("SHOW FIELD KEYS%1$s%2$s; SHOW TAG KEYS%1$s%2$s",
					databaseRestriction(catalog),
					getWithClause(tableNamePattern)))),
			new String[] { "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME",
				"COLUMN_SIZE", "BUFFER_LENGTH", "DECIMAL_DIGITS", "NUM_PREC_RADIX", "NULLABLE", "REMARKS", "COLUMN_DEF",
				"SQL_DATA_TYPE", "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH", "ORDINAL_POSITION", "IS_NULLABLE",
				"SCOPE_CATALOG", "SCOPE_SCHEMA", "SCOPE_TABLE", "SOURCE_DATA_TYPE", "IS_AUTOINCREMENT",
				"IS_GENERATEDCOLUMN", },
			new Object[] { null, null, null, null, Types.VARCHAR, "string", null, null, null, null, true, null, null,
				null, null, null, null, true, null, null, Types.VARCHAR, null, null, null }, catalog, null);
	}

	// Upstream reports columns as "all fields (in SHOW FIELD KEYS order), then all tags (in
	// SHOW TAG KEYS order)", and never mentions InfluxDB's implicit "time" column at all (it
	// isn't a real field or tag). But an actual `SELECT * FROM table` always returns "time"
	// first, followed by every field and tag *interleaved alphabetically* by name - a
	// completely different column order/count. SQuirreL's Content tab builds its displayed
	// column headers from this metadata *before* running that query, then reads the query's
	// result positionally against those headers, so any mismatch here shows up as data under
	// the wrong header (e.g. a "balance" header showing the row's real "time" value, and
	// everything drifting from there for the rest of the row).
	//
	// Fix: merge the field-key and tag-key rows from both statements into one series, sort
	// them by name the same way InfluxDB itself orders SELECT * columns, and prepend a
	// synthetic "time" row - in the same (fieldKey, fieldType) shape SHOW FIELD KEYS itself
	// returns - so this metadata's column list has the same order and count as what a real
	// query (either this driver's explicit `SELECT tbl.col1,tbl.col2,...,tbl.time` built by
	// ContentsTab.gatherColumnsForContentSelect, or a plain `SELECT *`) actually returns:
	// "time" always comes back first, regardless of where it was written in the SELECT list.
	//
	// (An earlier version of this fix put "time" *last* here, worked around a "query returns
	// zero rows when time is the first selected column" symptom by placing it last, and
	// left a comment saying so. That symptom turned out to be a red herring caused by a
	// separate bug in InfluxDbConnection.nativeSQL() - its alias-stripping regex only
	// matched "tbl." when preceded by whitespace, so in a comma-separated column list like
	// "tbl.time,tbl.atr20" every reference after the first kept its unstripped "tbl." prefix
	// and InfluxDB silently returned that column as always-null, which looked like "the
	// whole query returned nothing" depending on which column ended up first. With that
	// regex fixed (see InfluxDbConnection.java), time-first queries return correct data in
	// the correct order like any other column position, so there's no more reason to misalign
	// this metadata from SELECT *'s real order.)
	private static InfluxDbResultSet normalizeColumns(InfluxDbResultSet resultSet) {
		List<QueryResult.Result> results = resultSet.getResults();
		if (results == null || results.isEmpty()) {
			return resultSet;
		}

		String measurementName = results.stream()
			.map(QueryResult.Result::getSeries)
			.filter(series -> series != null && !series.isEmpty())
			.flatMap(List::stream)
			.map(QueryResult.Series::getName)
			.filter(name -> name != null)
			.findFirst()
			.orElse(null);

		List<List<Object>> merged = new ArrayList<>();
		for (QueryResult.Result result : results) {
			List<QueryResult.Series> series = result.getSeries();
			if (series == null) {
				continue;
			}
			for (QueryResult.Series s : series) {
				if (s.getValues() == null) {
					continue;
				}
				for (List<Object> row : s.getValues()) {
					// SHOW FIELD KEYS rows are [fieldKey, fieldType] (2 elements), but SHOW
					// TAG KEYS rows are just [tagKey] (1 element) - InfluxDB tags don't have
					// a declared type the way fields do. Pad tag rows to the same 2-element
					// shape so every row this metadata reports can be read uniformly; "tag"
					// is a reasonable stand-in type name for something that's always a string.
					merged.add(row.size() >= 2 ? row : List.of(row.get(0), "tag"));
				}
			}
		}
		merged.sort(Comparator.comparing(row -> String.valueOf(row.get(0))));
		merged.add(0, List.of("time", "string"));

		QueryResult.Series combined = new QueryResult.Series();
		combined.setName(measurementName);
		combined.setColumns(List.of("fieldKey", "fieldType"));
		combined.setValues(merged);

		QueryResult.Result combinedResult = new QueryResult.Result();
		combinedResult.setSeries(List.of(combined));

		return new InfluxDbResultSet(resultSet.getStatement(), List.of(combinedResult));
	}

	@Override protected int remapIndex(int columnIndex) {
		switch (columnIndex) {
		case 4:
			return 1;
		case 5:
		case 6:
		case 22:
			return 2;
		default:
			return 0;
		}
	}

	@Override protected Object mapOrDefault(int columnIndex, IntFunction<Object> function) {
		if (columnIndex == 1) {
			return catalog == null ? super.mapOrDefault(columnIndex, function) : catalog;
		} else if (columnIndex == 3 || columnIndex == 21) {
			return getMetaData().getTableName(columnIndex);
		} else if (columnIndex == 5 || columnIndex == 22) {
			// Upstream hard-coded DATA_TYPE (and SOURCE_DATA_TYPE) to Types.NUMERIC for
			// every column, field and tag alike. SQuirreL's Content tab uses this value
			// (via CellComponentFactory) to pick which cell renderer/parser to use for
			// each column, so a string tag (exchange, instrument, interval) or the
			// synthetic "time" row added in normalizeColumns() above - both really
			// Types.VARCHAR - got rendered with DataTypeBigDecimal, which threw
			// NumberFormatException trying to parse e.g. "GrvtFUT" or an ISO timestamp
			// as a decimal. Map the real fieldType text (row index 2, "float"/
			// "integer"/"boolean"/"string"/"tag") to the matching java.sql.Types
			// constant instead.
			return sqlTypeForFieldType(String.valueOf(function.apply(2)));
		} else {
			return super.mapOrDefault(columnIndex, function);
		}
	}

	private static int sqlTypeForFieldType(String fieldType) {
		if (fieldType == null) {
			return Types.VARCHAR;
		}
		return switch (fieldType.toLowerCase(Locale.ROOT)) {
			case "float" -> Types.DOUBLE;
			case "integer" -> Types.BIGINT;
			case "boolean" -> Types.BOOLEAN;
			default -> Types.VARCHAR;
		};
	}
}
