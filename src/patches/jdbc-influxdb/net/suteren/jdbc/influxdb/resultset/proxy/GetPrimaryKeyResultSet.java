package net.suteren.jdbc.influxdb.resultset.proxy;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntFunction;

import org.influxdb.dto.QueryResult;

import net.suteren.jdbc.influxdb.InfluxDbConnection;
import net.suteren.jdbc.influxdb.resultset.InfluxDbResultSet;

// New class, not part of upstream net.suteren.jdbc.influxdb:influxdb-jdbc:0.2.6 -
// InfluxDbMetadata.getPrimaryKeys() there just returns null unconditionally. SQuirreL's
// per-table "Primary Key" tab calls it directly and NPEs on that null result
// (ResultSetWrapper.getResultSet() being null), and separately, the "Edit 'WHERE'
// columns" dialog's "Use PK" button (see EditWhereColsCommand in SQuirreL's own core)
// relies on getPrimaryKeys() to know which columns are safe to build a delete/update
// WHERE clause from.
//
// InfluxDB has no formal primary key constraint, but a point's *identity* - what two
// writes need to match on to be the same point rather than two different ones - is its
// timestamp plus its full set of tag values (fields never factor into point identity,
// and are exactly what DataSetUpdateableTableModelImpl.count()'s "does this WHERE
// clause match exactly one row?" check needs to avoid, since InfluxQL's DELETE doesn't
// accept field comparisons in its WHERE clause at all). Reporting (time, tag1, tag2,
// ...) as this table's primary key is the closest honest equivalent, and happens to be
// exactly the column set a delete/update WHERE clause needs to stay valid.
public class GetPrimaryKeyResultSet extends AbstractProxyResultSet {
	public GetPrimaryKeyResultSet(InfluxDbConnection influxDbConnection, String tableNamePattern, String catalog)
		throws SQLException {
		super(withTimeColumn(influxDbConnection.createStatement()
				.executeQuery(String.format("SHOW TAG KEYS%s", getWithClause(tableNamePattern)))),
			new String[] { "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "KEY_SEQ", "PK_NAME" },
			new Object[] { null, null, null, null, 0, null }, catalog, null);
	}

	private static InfluxDbResultSet withTimeColumn(InfluxDbResultSet resultSet) {
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

		List<String> tagNames = new ArrayList<>();
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
					tagNames.add(String.valueOf(row.get(0)));
				}
			}
		}
		tagNames.sort(Comparator.naturalOrder());

		List<List<Object>> merged = new ArrayList<>();
		merged.add(List.of("time", 1));
		int seq = 2;
		for (String tagName : tagNames) {
			merged.add(List.of(tagName, seq++));
		}

		QueryResult.Series combined = new QueryResult.Series();
		combined.setName(measurementName);
		combined.setColumns(List.of("tagKey", "keySeq"));
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
			return 2;
		default:
			return 0;
		}
	}

	@Override protected Object mapOrDefault(int columnIndex, IntFunction<Object> function) {
		if (columnIndex == 1) {
			return catalog == null ? super.mapOrDefault(columnIndex, function) : catalog;
		} else if (columnIndex == 3) {
			return getMetaData().getTableName(columnIndex);
		} else {
			return super.mapOrDefault(columnIndex, function);
		}
	}
}
