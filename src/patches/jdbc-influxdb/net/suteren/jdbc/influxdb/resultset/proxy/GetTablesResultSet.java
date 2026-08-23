package net.suteren.jdbc.influxdb.resultset.proxy;

import java.sql.SQLException;
import java.util.function.IntFunction;

import net.suteren.jdbc.influxdb.InfluxDbConnection;
import net.suteren.jdbc.influxdb.resultset.InfluxDbResultSet;

// Patched copy of net.suteren.jdbc.influxdb:influxdb-jdbc:0.2.6's GetTablesResultSet
// (Apache-2.0) - see pom.xml's patch-influxdb-driver execution and
// measurementRestriction()/defaultRetentionPolicy() below for the actual changes.
public class GetTablesResultSet extends AbstractProxyResultSet {
	public GetTablesResultSet(InfluxDbConnection influxDbConnection, String tableNamePattern, String rawCatalog)
		throws SQLException {
		super(influxDbConnection.createStatement().executeQuery(String.format("SHOW MEASUREMENTS%s%s",
				databaseRestriction(effectiveCatalog(influxDbConnection, rawCatalog)), measurementRestriction(tableNamePattern))),
			new String[] { "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "TABLE_TYPE", "REMARKS", "TYPE_CAT", "TYPE_SCHEM",
				"TYPE_NAME", "SELF_REFERENCING_COL_NAME", "REF_GENERATION" },
			new String[] { null,
				defaultRetentionPolicy(influxDbConnection, effectiveCatalog(influxDbConnection, rawCatalog)), null,
				"TABLE", null, null, null, null, null, null },
			effectiveCatalog(influxDbConnection, rawCatalog), null);
	}

	// SQuirreL's very first, automatic getTables() call after connecting (the one that
	// populates the object tree) passes catalog=null - "no filter", a normal JDBC
	// convention - not the connected database's name. defaultRetentionPolicy() needs an
	// actual database name to query "SHOW RETENTION POLICIES ON <db>", so a null catalog
	// here made that first load report TABLE_SCHEM=null again (the original empty
	// "TABLE" node bug), fixed only once something explicitly passed a real catalog
	// (SQuirreL's "Refresh Item" does, since it operates on an already-known tree node).
	// This plugin only ever really deals with one database per connection anyway (the
	// JDBC URL's db= parameter), so falling back to the connection's own catalog
	// (InfluxDbConnection.getCatalog(), now set from that same db= value - see its
	// constructor) whenever the caller didn't specify one makes the very first load work
	// the same as every later one.
	private static String effectiveCatalog(InfluxDbConnection influxDbConnection, String catalog) {
		return catalog != null && !catalog.isBlank() ? catalog : influxDbConnection.getCatalog();
	}

	private static String measurementRestriction(String tableNamePattern) {
		// Upstream built `WITH MEASUREMENT =~ /<tableNamePattern>/` unconditionally.
		// SQuirreL passes the SQL LIKE wildcard "%" (meaning "all tables") when listing
		// every measurement in the object tree, and "%" is not a special character in
		// InfluxQL's regex syntax - it only matched a literal "%", so the "TABLE" node
		// always came back empty instead of listing real measurements.
		return tableNamePattern != null && !tableNamePattern.isBlank() && !"%".equals(tableNamePattern) ?
			String.format(" WITH MEASUREMENT =~ /%s/", tableNamePattern) : "";
	}

	// Upstream always reported TABLE_SCHEM as null. SQuirreL's SchemaInfo caches every
	// table once, then filters that cache per tree node with
	// `schema.equalsIgnoreCase(tableInfo.getSchemaName())` when a specific schema (here,
	// retention policy - see GetSchemaResultSet) is being drilled into. A null
	// TABLE_SCHEM never equals a non-null schema name, so every measurement was silently
	// filtered out of every retention-policy node - no error, no log line, just an
	// always-empty "TABLE" folder. Reporting the database's default retention policy
	// (the common case: one RP per database, exactly what `influx v1 dbrp create`/the
	// auto-generated virtual DBRP mapping also assumes) makes that comparison match.
	// Measurements that actually only have data in a *non-default* retention policy
	// will still not show up under that RP's node - InfluxQL's SHOW MEASUREMENTS has no
	// per-RP scoping to report a more precise answer from.
	private static String defaultRetentionPolicy(InfluxDbConnection influxDbConnection, String catalog) {
		if (catalog == null || catalog.isBlank()) {
			return null;
		}
		try (InfluxDbResultSet rs = influxDbConnection.createStatement()
			.executeQuery(String.format("SHOW RETENTION POLICIES ON \"%s\"", quoteName(catalog)))) {
			while (rs.next()) {
				if (Boolean.TRUE.equals(rs.getObject(5))) {
					return String.valueOf(rs.getObject(1));
				}
			}
		} catch (SQLException e) {
			return null;
		}
		return null;
	}

	@Override protected int remapIndex(int columnIndex) {
		return columnIndex == 3 ? 1 : 0;
	}

	@Override protected Object mapOrDefault(int columnIndex, IntFunction<Object> function) {
		if (columnIndex == 1) {
			return catalog == null ? super.mapOrDefault(columnIndex, function) : catalog;
		} else {
			return super.mapOrDefault(columnIndex, function);
		}
	}
}
