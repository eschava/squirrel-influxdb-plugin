package net.suteren.jdbc.influxdb;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.util.regex.Pattern;

import net.suteren.jdbc.Version;
import net.suteren.jdbc.influxdb.resultset.proxy.AbstractProxyResultSet;
import net.suteren.jdbc.influxdb.resultset.proxy.GetBestRowIdentifierResultSet;
import net.suteren.jdbc.influxdb.resultset.proxy.GetCatalogResultSet;
import net.suteren.jdbc.influxdb.resultset.proxy.GetColumnResultSet;
import net.suteren.jdbc.influxdb.resultset.proxy.GetEmptyResultSet;
import net.suteren.jdbc.influxdb.resultset.proxy.GetIndexResultSet;
import net.suteren.jdbc.influxdb.resultset.proxy.GetPrimaryKeyResultSet;
import net.suteren.jdbc.influxdb.resultset.proxy.GetSchemaResultSet;
import net.suteren.jdbc.influxdb.resultset.proxy.GetTablesResultSet;

// Patched copy of net.suteren.jdbc.influxdb:influxdb-jdbc:0.2.6's InfluxDbMetadata
// (Apache-2.0) - see pom.xml's patch-influxdb-driver execution. Upstream had roughly
// twenty DatabaseMetaData methods returning a bare `null` for "no data" instead of an
// empty ResultSet - fine for methods SQuirreL never calls, but every one backing a
// per-table info tab (Primary Key, Exported Keys, Imported Keys, Row IDs, Versions,
// Privileges, Column Privileges) NPEs SQuirreL's own tab-rendering code
// (ResultSetWrapper.getResultSet() returns that null, then .getMetaData() on it
// throws) the moment the user clicks that tab. getPrimaryKeys() and
// getBestRowIdentifier() are now real (time + tags - see GetPrimaryKeyResultSet's
// comment for why); the rest of that group now return a genuinely empty ResultSet
// (see GetEmptyResultSet) instead of null, since InfluxDB really has no equivalent of
// a foreign key, a privilege grant, or row versioning - "no rows" is the honest
// answer, "crash" isn't.
public class InfluxDbMetadata implements DatabaseMetaData {
	private final String url;
	private final String userName;
	private final InfluxDbDriver influxDbDriver;
	private final InfluxDbConnection influxDbConnection;
	private static final Pattern PERCENT_PATTERN = Pattern.compile("%");

	public InfluxDbMetadata(String url, String userName, InfluxDbDriver influxDbDriver, InfluxDbConnection influxDbConnection) {
		this.url = url;
		this.userName = userName;
		this.influxDbDriver = influxDbDriver;
		this.influxDbConnection = influxDbConnection;
	}

	public boolean allProceduresAreCallable() {
		return false;
	}

	public boolean allTablesAreSelectable() {
		return true;
	}

	public String getURL() {
		return this.url;
	}

	public String getUserName() {
		return this.userName;
	}

	public boolean isReadOnly() {
		return true;
	}

	public boolean nullsAreSortedHigh() {
		return false;
	}

	public boolean nullsAreSortedLow() {
		return false;
	}

	public boolean nullsAreSortedAtStart() {
		return false;
	}

	public boolean nullsAreSortedAtEnd() {
		return false;
	}

	public String getDatabaseProductName() {
		return "InfluxDB";
	}

	public String getDatabaseProductVersion() {
		return this.influxDbConnection.getClient().version();
	}

	public String getDriverName() {
		return "InfluxDB JDBC driver";
	}

	public String getDriverVersion() {
		return Version.getVersion().toString();
	}

	public int getDriverMajorVersion() {
		return this.influxDbDriver.getMajorVersion();
	}

	public int getDriverMinorVersion() {
		return this.influxDbDriver.getMinorVersion();
	}

	public boolean usesLocalFiles() {
		return false;
	}

	public boolean usesLocalFilePerTable() {
		return false;
	}

	public boolean supportsMixedCaseIdentifiers() {
		return true;
	}

	public boolean storesUpperCaseIdentifiers() {
		return false;
	}

	public boolean storesLowerCaseIdentifiers() {
		return false;
	}

	public boolean storesMixedCaseIdentifiers() {
		return false;
	}

	public boolean supportsMixedCaseQuotedIdentifiers() {
		return true;
	}

	public boolean storesUpperCaseQuotedIdentifiers() {
		return false;
	}

	public boolean storesLowerCaseQuotedIdentifiers() {
		return false;
	}

	public boolean storesMixedCaseQuotedIdentifiers() {
		return false;
	}

	public String getIdentifierQuoteString() {
		return "\"";
	}

	public String getSQLKeywords() {
		return "measurement,field,tag,series,select,database";
	}

	public String getNumericFunctions() {
		return "COUNT([ * | <field_key> | /<regular_expression>/ ]),DISTINCT( [ <field_key> | /<regular_expression>/ ] ),DISTINCT( [ <field_key> | /<regular_expression>/ ] ),DISTINCT( [ <field_key> | /<regular_expression>/ ] ),MEDIAN( [ * | <field_key> | /<regular_expression>/ ] ),MODE( [ * | <field_key> | /<regular_expression>/ ] ),SPREAD( [ * | <field_key> | /<regular_expression>/ ] ),STDDEV( [ * | <field_key> | /<regular_expression>/ ] ),SUM( [ * | <field_key> | /<regular_expression>/ ] ),BOTTOM(<field_key>[,<tag_key(s)>],<N>),FIRST(<field_key>),FIRST(<field_key>),MAX(<field_key>),MIN(<field_key>),PERCENTILE(<field_key>, <N>),SAMPLE(<field_key>, <N>),TOP( <field_key>[,<tag_key(s)>],<N> ),ABS( [ * | <field_key> ] ),ACOS( [ * | <field_key> ] ),ASIN( [ * | <field_key> ] ),ATAN( [ * | <field_key> ] ),ATAN2( [ * | <field_key> | num ], [ <field_key> | num ] ),CEIL( [ * | <field_key> ] ),COS( [ * | <field_key> ] ),CUMULATIVE_SUM(<function>( [ * | <field_key> | /<regular_expression>/ ] )),DERIVATIVE( [ * | <field_key> | /<regular_expression>/ ] [ , <unit> ] ),DIFFERENCE( [ * | <field_key> | /<regular_expression>/ ] ),ELAPSED( [ * | <field_key> | /<regular_expression>/ ] [ , <unit> ] ),EXP( [ * | <field_key> ] ),EXP( [ * | <field_key> ] ),LN( [ * | <field_key> ] ),LOG( [ * | <field_key> ], <b> ),LOG2( [ * | <field_key> ] ),LOG10( [ * | <field_key> ] ),MOVING_AVERAGE( [ * | <field_key> | /<regular_expression>/ ] , <N> ),NON_NEGATIVE_DERIVATIVE( [ * | <field_key> | /<regular_expression>/ ] [ , <unit> ] ),NON_NEGATIVE_DIFFERENCE( [ * | <field_key> | /<regular_expression>/ ] ),POW( [ * | <field_key> ], <x> ),ROUND( [ * | <field_key> ] ),SIN( [ * | <field_key> ] ),SQRT( [ * | <field_key> ] )TAN( [ * | <field_key> ] ),HOLT_WINTERS[_WITH-FIT](<function>(<field_key>),<N>,<S>),CHANDE_MOMENTUM_OSCILLATOR(PERIOD, HOLD_PERIOD),EXPONENTIAL_MOVING_AVERAGE(PERIOD, HOLD_PERIOD),DOUBLE_EXPONENTIAL_MOVING_AVERAGE(PERIOD, HOLD_PERIOD),KAUFMANS_EFFICIENCY_RATIO(PERIOD, HOLD_PERIOD),KAUFMANS_ADAPTIVE_MOVING_AVERAGE(PERIOD, HOLD_PERIOD),TRIPLE_EXPONENTIAL_MOVING_AVERAGE(PERIOD, HOLD_PERIOD),TRIPLE_EXPONENTIAL_DERIVATIVE(PERIOD, HOLD_PERIOD),RELATIVE_STRENGTH_INDEX(PERIOD, HOLD_PERIOD)";
	}

	public String getStringFunctions() {
		return "";
	}

	public String getSystemFunctions() {
		return "";
	}

	public String getTimeDateFunctions() {
		return "";
	}

	public String getSearchStringEscape() {
		return "'";
	}

	public String getExtraNameCharacters() {
		return "";
	}

	public boolean supportsAlterTableWithAddColumn() {
		return false;
	}

	public boolean supportsAlterTableWithDropColumn() {
		return false;
	}

	public boolean supportsColumnAliasing() {
		return false;
	}

	public boolean nullPlusNonNullIsNull() {
		return false;
	}

	public boolean supportsConvert() {
		return false;
	}

	public boolean supportsConvert(int fromType, int toType) {
		return false;
	}

	public boolean supportsTableCorrelationNames() {
		return false;
	}

	public boolean supportsDifferentTableCorrelationNames() {
		return false;
	}

	public boolean supportsExpressionsInOrderBy() {
		return false;
	}

	public boolean supportsOrderByUnrelated() {
		return false;
	}

	public boolean supportsGroupBy() {
		return true;
	}

	public boolean supportsGroupByUnrelated() {
		return false;
	}

	public boolean supportsGroupByBeyondSelect() {
		return true;
	}

	public boolean supportsLikeEscapeClause() {
		return true;
	}

	public boolean supportsMultipleResultSets() {
		return true;
	}

	public boolean supportsMultipleTransactions() {
		return false;
	}

	public boolean supportsNonNullableColumns() {
		return false;
	}

	public boolean supportsMinimumSQLGrammar() {
		return false;
	}

	public boolean supportsCoreSQLGrammar() {
		return false;
	}

	public boolean supportsExtendedSQLGrammar() {
		return false;
	}

	public boolean supportsANSI92EntryLevelSQL() {
		return false;
	}

	public boolean supportsANSI92IntermediateSQL() {
		return false;
	}

	public boolean supportsANSI92FullSQL() {
		return false;
	}

	public boolean supportsIntegrityEnhancementFacility() {
		return false;
	}

	public boolean supportsOuterJoins() {
		return false;
	}

	public boolean supportsFullOuterJoins() {
		return false;
	}

	public boolean supportsLimitedOuterJoins() {
		return false;
	}

	public String getSchemaTerm() {
		return "retention";
	}

	public String getProcedureTerm() {
		return "";
	}

	public String getCatalogTerm() {
		return "database";
	}

	public boolean isCatalogAtStart() {
		return true;
	}

	public String getCatalogSeparator() {
		return ".";
	}

	public boolean supportsSchemasInDataManipulation() {
		return true;
	}

	public boolean supportsSchemasInProcedureCalls() {
		return true;
	}

	public boolean supportsSchemasInTableDefinitions() {
		return true;
	}

	public boolean supportsSchemasInIndexDefinitions() {
		return true;
	}

	public boolean supportsSchemasInPrivilegeDefinitions() {
		return true;
	}

	public boolean supportsCatalogsInDataManipulation() {
		return true;
	}

	public boolean supportsCatalogsInProcedureCalls() {
		return true;
	}

	public boolean supportsCatalogsInTableDefinitions() {
		return true;
	}

	public boolean supportsCatalogsInIndexDefinitions() {
		return true;
	}

	public boolean supportsCatalogsInPrivilegeDefinitions() {
		return true;
	}

	public boolean supportsPositionedDelete() {
		return false;
	}

	public boolean supportsPositionedUpdate() {
		return false;
	}

	public boolean supportsSelectForUpdate() {
		return false;
	}

	public boolean supportsStoredProcedures() {
		return false;
	}

	public boolean supportsSubqueriesInComparisons() {
		return false;
	}

	public boolean supportsSubqueriesInExists() {
		return false;
	}

	public boolean supportsSubqueriesInIns() {
		return false;
	}

	public boolean supportsSubqueriesInQuantifieds() {
		return false;
	}

	public boolean supportsCorrelatedSubqueries() {
		return false;
	}

	public boolean supportsUnion() {
		return false;
	}

	public boolean supportsUnionAll() {
		return false;
	}

	public boolean supportsOpenCursorsAcrossCommit() {
		return false;
	}

	public boolean supportsOpenCursorsAcrossRollback() {
		return false;
	}

	public boolean supportsOpenStatementsAcrossCommit() {
		return false;
	}

	public boolean supportsOpenStatementsAcrossRollback() {
		return false;
	}

	public int getMaxBinaryLiteralLength() {
		return 0;
	}

	public int getMaxCharLiteralLength() {
		return 0;
	}

	public int getMaxColumnNameLength() {
		return 0;
	}

	public int getMaxColumnsInGroupBy() {
		return 0;
	}

	public int getMaxColumnsInIndex() {
		return 0;
	}

	public int getMaxColumnsInOrderBy() {
		return 0;
	}

	public int getMaxColumnsInSelect() {
		return 0;
	}

	public int getMaxColumnsInTable() {
		return 0;
	}

	public int getMaxConnections() {
		return 0;
	}

	public int getMaxCursorNameLength() {
		return 0;
	}

	public int getMaxIndexLength() {
		return 0;
	}

	public int getMaxSchemaNameLength() {
		return 0;
	}

	public int getMaxProcedureNameLength() {
		return 0;
	}

	public int getMaxCatalogNameLength() {
		return 0;
	}

	public int getMaxRowSize() {
		return 0;
	}

	public boolean doesMaxRowSizeIncludeBlobs() {
		return false;
	}

	public int getMaxStatementLength() {
		return 0;
	}

	public int getMaxStatements() {
		return 0;
	}

	public int getMaxTableNameLength() {
		return 0;
	}

	public int getMaxTablesInSelect() {
		return 0;
	}

	public int getMaxUserNameLength() {
		return 0;
	}

	public int getDefaultTransactionIsolation() {
		return 0;
	}

	public boolean supportsTransactions() {
		return false;
	}

	public boolean supportsTransactionIsolationLevel(int level) {
		return false;
	}

	public boolean supportsDataDefinitionAndDataManipulationTransactions() {
		return false;
	}

	public boolean supportsDataManipulationTransactionsOnly() {
		return false;
	}

	public boolean dataDefinitionCausesTransactionCommit() {
		return false;
	}

	public boolean dataDefinitionIgnoredInTransactions() {
		return false;
	}

	public ResultSet getProcedures(String catalog, String schemaPattern, String procedureNamePattern) {
		return null;
	}

	public ResultSet getProcedureColumns(String catalog, String schemaPattern, String procedureNamePattern, String columnNamePattern) {
		return null;
	}

	public GetTablesResultSet getTables(String catalog, String schemaPattern, String tableNamePattern, String[] types) throws SQLException {
		return new GetTablesResultSet(this.influxDbConnection, tableNamePattern != null && !PERCENT_PATTERN.matcher(tableNamePattern).matches() ? tableNamePattern : null, catalog);
	}

	public GetSchemaResultSet getSchemas() throws SQLException {
		return this.getSchemas((String) null, (String) null);
	}

	public GetCatalogResultSet getCatalogs() throws SQLException {
		return new GetCatalogResultSet(this.influxDbConnection);
	}

	public ResultSet getTableTypes() {
		return null;
	}

	public AbstractProxyResultSet getColumns(String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern) throws SQLException {
		return new GetColumnResultSet(this.influxDbConnection, tableNamePattern != null && !PERCENT_PATTERN.matcher(tableNamePattern).matches() ? tableNamePattern : null, catalog);
	}

	public ResultSet getColumnPrivileges(String catalog, String schema, String table, String columnNamePattern) throws SQLException {
		return new GetEmptyResultSet(this.influxDbConnection,
			new String[] { "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "GRANTOR", "GRANTEE", "PRIVILEGE",
				"IS_GRANTABLE" });
	}

	public ResultSet getTablePrivileges(String catalog, String schemaPattern, String tableNamePattern) throws SQLException {
		return new GetEmptyResultSet(this.influxDbConnection,
			new String[] { "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "GRANTOR", "GRANTEE", "PRIVILEGE", "IS_GRANTABLE" });
	}

	public ResultSet getBestRowIdentifier(String catalog, String schema, String table, int scope, boolean nullable) throws SQLException {
		return new GetBestRowIdentifierResultSet(this.influxDbConnection, table != null && !PERCENT_PATTERN.matcher(table).matches() ? table : null);
	}

	public ResultSet getVersionColumns(String catalog, String schema, String table) throws SQLException {
		return new GetEmptyResultSet(this.influxDbConnection,
			new String[] { "SCOPE", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME", "COLUMN_SIZE", "BUFFER_LENGTH",
				"DECIMAL_DIGITS", "PSEUDO_COLUMN" });
	}

	public ResultSet getPrimaryKeys(String catalog, String schema, String table) throws SQLException {
		return new GetPrimaryKeyResultSet(this.influxDbConnection, table != null && !PERCENT_PATTERN.matcher(table).matches() ? table : null, catalog);
	}

	public ResultSet getImportedKeys(String catalog, String schema, String table) throws SQLException {
		return new GetEmptyResultSet(this.influxDbConnection, CROSS_REFERENCE_COLUMNS);
	}

	public ResultSet getExportedKeys(String catalog, String schema, String table) throws SQLException {
		return new GetEmptyResultSet(this.influxDbConnection, CROSS_REFERENCE_COLUMNS);
	}

	public ResultSet getCrossReference(String parentCatalog, String parentSchema, String parentTable, String foreignCatalog, String foreignSchema, String foreignTable) throws SQLException {
		return new GetEmptyResultSet(this.influxDbConnection, CROSS_REFERENCE_COLUMNS);
	}

	private static final String[] CROSS_REFERENCE_COLUMNS = { "PKTABLE_CAT", "PKTABLE_SCHEM", "PKTABLE_NAME",
		"PKCOLUMN_NAME", "FKTABLE_CAT", "FKTABLE_SCHEM", "FKTABLE_NAME", "FKCOLUMN_NAME", "KEY_SEQ", "UPDATE_RULE",
		"DELETE_RULE", "FK_NAME", "PK_NAME", "DEFERRABILITY" };

	public ResultSet getTypeInfo() {
		return null;
	}

	public GetIndexResultSet getIndexInfo(String catalog, String schema, String table, boolean unique, boolean approximate) throws SQLException {
		return new GetIndexResultSet(this.influxDbConnection, table != null && !PERCENT_PATTERN.matcher(table).matches() ? table : null);
	}

	public boolean supportsResultSetType(int type) {
		return true;
	}

	public boolean supportsResultSetConcurrency(int type, int concurrency) {
		return false;
	}

	public boolean ownUpdatesAreVisible(int type) {
		return false;
	}

	public boolean ownDeletesAreVisible(int type) {
		return false;
	}

	public boolean ownInsertsAreVisible(int type) {
		return false;
	}

	public boolean othersUpdatesAreVisible(int type) {
		return false;
	}

	public boolean othersDeletesAreVisible(int type) {
		return false;
	}

	public boolean othersInsertsAreVisible(int type) {
		return false;
	}

	public boolean updatesAreDetected(int type) {
		return false;
	}

	public boolean deletesAreDetected(int type) {
		return false;
	}

	public boolean insertsAreDetected(int type) {
		return false;
	}

	public boolean supportsBatchUpdates() {
		return false;
	}

	public ResultSet getUDTs(String catalog, String schemaPattern, String typeNamePattern, int[] types) {
		return null;
	}

	public Connection getConnection() {
		return this.influxDbConnection;
	}

	public boolean supportsSavepoints() {
		return false;
	}

	public boolean supportsNamedParameters() {
		return false;
	}

	public boolean supportsMultipleOpenResults() {
		return false;
	}

	public boolean supportsGetGeneratedKeys() {
		return false;
	}

	public ResultSet getSuperTypes(String catalog, String schemaPattern, String typeNamePattern) {
		return null;
	}

	public ResultSet getSuperTables(String catalog, String schemaPattern, String tableNamePattern) {
		return null;
	}

	public ResultSet getAttributes(String catalog, String schemaPattern, String typeNamePattern, String attributeNamePattern) {
		return null;
	}

	public boolean supportsResultSetHoldability(int holdability) {
		return false;
	}

	public int getResultSetHoldability() {
		return 0;
	}

	public int getDatabaseMajorVersion() {
		return Integer.parseInt(this.getDatabaseProductVersion().split("\\.")[0]);
	}

	public int getDatabaseMinorVersion() {
		return Integer.parseInt(this.getDatabaseProductVersion().split("\\.")[1]);
	}

	public int getJDBCMajorVersion() {
		return 3;
	}

	public int getJDBCMinorVersion() {
		return 0;
	}

	public int getSQLStateType() {
		return 1;
	}

	public boolean locatorsUpdateCopy() {
		return false;
	}

	public boolean supportsStatementPooling() {
		return false;
	}

	public RowIdLifetime getRowIdLifetime() {
		return null;
	}

	public GetSchemaResultSet getSchemas(String catalog, String schemaPattern) throws SQLException {
		return new GetSchemaResultSet(this.influxDbConnection, catalog);
	}

	public boolean supportsStoredFunctionsUsingCallSyntax() {
		return false;
	}

	public boolean autoCommitFailureClosesAllResultSets() {
		return false;
	}

	public ResultSet getClientInfoProperties() {
		return null;
	}

	public ResultSet getFunctions(String catalog, String schemaPattern, String functionNamePattern) {
		return null;
	}

	public ResultSet getFunctionColumns(String catalog, String schemaPattern, String functionNamePattern, String columnNamePattern) {
		return null;
	}

	public ResultSet getPseudoColumns(String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern) {
		return null;
	}

	public boolean generatedKeyAlwaysReturned() {
		return false;
	}

	public Object unwrap(Class iface) {
		return null;
	}

	public boolean isWrapperFor(Class iface) {
		return false;
	}

	public InfluxDbDriver getDriver() {
		return this.influxDbDriver;
	}
}
