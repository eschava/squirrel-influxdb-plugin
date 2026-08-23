package net.suteren.jdbc.influxdb.resultset.proxy;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

import org.influxdb.InfluxDB;
import org.influxdb.dto.Query;
import org.influxdb.dto.QueryResult;

import net.suteren.jdbc.influxdb.InfluxDbConnection;
import net.suteren.jdbc.influxdb.resultset.InfluxDbResultSet;
import net.suteren.jdbc.influxdb.statement.InfluxDbStatement;

// Patched copy of net.suteren.jdbc.influxdb:influxdb-jdbc:0.2.6's GetSchemaResultSet
// (Apache-2.0) - see pom.xml's patch-influxdb-driver execution and prepareResults()
// below for the actual change.
public class GetSchemaResultSet extends AbstractProxyResultSet {
	public GetSchemaResultSet(InfluxDbConnection influxDbConnection, String catalog) throws SQLException {
		super(prepareResults(influxDbConnection, catalog), new String[] { "TABLE_SCHEM", "TABLE_CATALOG" },
			new String[] { null, null }, catalog, null);
	}

	private static InfluxDbResultSet prepareResults(InfluxDbConnection influxDbConnection, String catalog)
		throws SQLException {
		if (catalog == null) {
			InfluxDB client = influxDbConnection.getClient();
			List<Object> databases = client.query(new Query("SHOW DATABASES")).getResults().stream()
				.map(QueryResult.Result::getSeries)
				.flatMap(Collection::stream)
				.map(QueryResult.Series::getValues)
				.flatMap(Collection::stream)
				.map(v -> v.get(0))
				.collect(Collectors.toList());

			// Every database normally has its own default retention policy, and it's
			// almost always named "autogen" (InfluxDB's own default, and what `influx v1
			// dbrp create`'s auto-generated mapping assumes too) - so this connection's
			// databases very commonly share the exact same schema name. Upstream reported
			// one (schema, database) row per database unconditionally, e.g. 4 separate
			// "autogen" rows for 4 databases that all happen to use that name. SQuirreL's
			// own DatabaseExpander.createSchemaNodes() has no deduplication of its own -
			// for a given catalog, it walks every entry in this combined list and adds a
			// tree node for each one that SchemaContainedInCatalogCheck.containedInCatalog()
			// says matches, with no "already added this name" check - so a name repeated
			// once per database that shares it became that same number of duplicate
			// "autogen" nodes under every single database in the tree, not just the one
			// each row was actually about.
			//
			// containedInCatalog() already has a documented way to say "this schema isn't
			// tied to one particular catalog" - a row with an empty/null TABLE_CATALOG
			// matches every catalog. So instead of one row per database, this now reports
			// each distinct schema name exactly once: with its real (single) database if
			// only one database actually has it, or with a null database if more than one
			// does - both correct, and neither produces a duplicate node per catalog.
			Map<String, LinkedHashSet<Object>> schemaNameToCatalogs = new LinkedHashMap<>();
			for (Object database : databases) {
				InfluxDbResultSet rps = influxDbConnection.createStatement()
					.executeQuery(String.format("SHOW RETENTION POLICIES ON \"%s\"", database));
				for (QueryResult.Result result : rps.getResults()) {
					List<QueryResult.Series> series = result.getSeries();
					if (series == null) {
						continue;
					}
					for (QueryResult.Series s : series) {
						if (s.getValues() == null) {
							continue;
						}
						for (List<Object> row : s.getValues()) {
							schemaNameToCatalogs.computeIfAbsent(String.valueOf(row.get(0)), k -> new LinkedHashSet<>())
								.add(database);
						}
					}
				}
			}

			List<List<Object>> results = new ArrayList<>();
			for (Map.Entry<String, LinkedHashSet<Object>> entry : schemaNameToCatalogs.entrySet()) {
				Object catalogForRow = entry.getValue().size() == 1 ? entry.getValue().iterator().next() : null;
				results.add(List.of(entry.getKey(), catalogForRow == null ? "" : catalogForRow));
			}

			QueryResult.Series s = new QueryResult.Series();
			s.setColumns(List.of("TABLE_SCHEM", "TABLE_CATALOG"));
			s.setName("SCHEMAS");
			s.setValues(results);
			QueryResult.Result r = new QueryResult.Result();
			r.setSeries(List.of(s));
			return new InfluxDbResultSet(new InfluxDbStatement(influxDbConnection, client), List.of(r));
		} else {
			InfluxDbResultSet retentionPolicies = influxDbConnection.createStatement()
				.executeQuery(String.format("SHOW RETENTION POLICIES ON \"%s\"", catalog));
			retentionPolicies.getResults().forEach(rx -> rx.getSeries().forEach(s -> s.setValues(new ArrayList<>(s.getValues()))));
			return retentionPolicies;
		}
	}

	@Override protected int remapIndex(int columnIndex) {
		return columnIndex >= 0 && columnIndex <= 2 ? columnIndex : 0;
	}

	@Override protected Object mapOrDefault(int columnIndex, IntFunction<Object> function) {
		if (columnIndex == 2) {
			return this.catalog == null ? super.mapOrDefault(columnIndex, function) : this.catalog;
		} else {
			return super.mapOrDefault(columnIndex, function);
		}
	}
}
