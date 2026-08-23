package net.suteren.jdbc.influxdb.resultset.proxy;

import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntFunction;

import org.influxdb.dto.QueryResult;

import net.suteren.jdbc.influxdb.InfluxDbConnection;
import net.suteren.jdbc.influxdb.resultset.InfluxDbResultSet;

// New class, not part of upstream net.suteren.jdbc.influxdb:influxdb-jdbc:0.2.6 -
// InfluxDbMetadata.getBestRowIdentifier() there just returns null unconditionally,
// which NPEs SQuirreL's per-table "Row IDs" tab. Same underlying answer as
// GetPrimaryKeyResultSet (see its comment for why): a point's identity is its
// timestamp plus its tag values, so that's what "best identifies a row" here too.
public class GetBestRowIdentifierResultSet extends AbstractProxyResultSet {
	public GetBestRowIdentifierResultSet(InfluxDbConnection influxDbConnection, String tableNamePattern)
		throws SQLException {
		super(withTimeColumn(influxDbConnection.createStatement()
				.executeQuery(String.format("SHOW TAG KEYS%s", getWithClause(tableNamePattern)))),
			new String[] { "SCOPE", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME", "COLUMN_SIZE", "BUFFER_LENGTH",
				"DECIMAL_DIGITS", "PSEUDO_COLUMN" },
			new Object[] { (short) DatabaseMetaData.bestRowSession, null, Types.VARCHAR, "VARCHAR", 0, 0, (short) 0,
				(short) DatabaseMetaData.bestRowNotPseudo });
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
		merged.add(List.of("time"));
		for (String tagName : tagNames) {
			merged.add(List.of(tagName));
		}

		QueryResult.Series combined = new QueryResult.Series();
		combined.setName(measurementName);
		combined.setColumns(List.of("tagKey"));
		combined.setValues(merged);

		QueryResult.Result combinedResult = new QueryResult.Result();
		combinedResult.setSeries(List.of(combined));

		return new InfluxDbResultSet(resultSet.getStatement(), List.of(combinedResult));
	}

	@Override protected int remapIndex(int columnIndex) {
		return columnIndex == 2 ? 1 : 0;
	}

	@Override protected Object mapOrDefault(int columnIndex, IntFunction<Object> function) {
		return super.mapOrDefault(columnIndex, function);
	}
}
