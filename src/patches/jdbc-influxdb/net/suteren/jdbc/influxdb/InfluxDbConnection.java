package net.suteren.jdbc.influxdb;

import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;

import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.influxdb.dto.Pong;

import net.suteren.jdbc.influxdb.statement.InfluxDbPreparedStatement;
import net.suteren.jdbc.influxdb.statement.InfluxDbStatement;

// Patched copy of net.suteren.jdbc.influxdb:influxdb-jdbc:0.2.6's InfluxDbConnection
// (Apache-2.0) - see pom.xml's patch-influxdb-driver execution. Changes are in the
// constructor (token auth and read/write/connect timeouts - see below) and
// nativeSQL() (see the comment there).
public class InfluxDbConnection implements Connection {
	private final InfluxDB influxDbClient;
	private final InfluxDbMetadata influxDbMetadata;
	private boolean isClosed;
	private final Logger log;
	private static final Pattern KEEP_ALIVE_SQL_PATTERN = Pattern.compile("\\s*SELECT\\s+['\"]keep\\s+alive['\"]\\s*.*", 34);
	private static final Pattern TABLE_ALIASES_SQL_PATTERN = Pattern.compile("\\s*SELECT\\s+(\\S+)\\s+FROM\\s+(\\S+)\\s+(?!where)(?:as\\s+)?((['\"]?)(\\S+)\\4)(.*)", 34);
	private static final Pattern TABLE_SCHEMA_SQL_PATTERN = Pattern.compile("\\s*SELECT\\s+(\\S+)\\s+FROM\\s+(?:(?:(((?:(?<!\\\\)[\"'])?)(\\S+)\\3)\\.)?(((?:(?<!\\\\)[\"'])?)(\\S+)\\6)\\.)?(((?:(?<!\\\\)[\"'])?)(\\S+)\\9(\\s.*)?)", 34);
	private static final Pattern DEFAULT_SCHEMA_PATTERN = Pattern.compile("\\s*SELECT\\s.*(['\"]?)default\\1\\..*\\sFROM\\s+.+", 34);
	// Finds (not matches-the-whole-string) every "DELETE FROM <qualified.name>" - see
	// nativeSQL() for why this can't require matching the entire input the way the other
	// patterns in this class do.
	private static final Pattern DELETE_QUALIFIED_NAME_PATTERN =
		Pattern.compile("(?i)\\bDELETE\\s+FROM\\s+((?:[^\\s.;]+\\.)*[^\\s.;]+)");
	// Finds every "FROM <possibly.qualified.name>" whose final (measurement) segment
	// isn't already quoted - see nativeSQL() for why this needs to run on every FROM,
	// not just SELECT's. Deliberately excludes "\"" from the segment character class so
	// an already-quoted final segment (its first character right after the last dot is
	// a '"') simply fails to match here and is left alone.
	private static final Pattern FROM_UNQUOTED_TABLE_PATTERN =
		Pattern.compile("(?i)\\bFROM\\s+((?:[^\\s.;,()\"]+\\.)*)([^\\s.;,()\"]+)");
	// org.influxdb-java's own default (an unconfigured `new OkHttpClient.Builder()`,
	// which upstream used for every connect(...) call that doesn't take a builder) is
	// OkHttp's own 10-second connect/read/write timeout - fine for typical SELECTs, but
	// an unconditional DELETE (no WHERE - SQuirreL's own "Delete Records" table-level
	// action) on a large measurement is a genuinely expensive server-side operation
	// (InfluxDB has to rewrite/tombstone TSM data across every shard the measurement
	// has), and 10s isn't enough - seen directly: `DELETE FROM <table>` on a
	// several-hundred-thousand-row measurement failed with
	// `java.net.SocketTimeoutException: Read timed out` well before InfluxDB was done.
	// 2 minutes is a much more realistic ceiling for a desktop SQL client's occasional
	// bulk operation; override with a `timeout=<seconds>` URL parameter for anything
	// still not long enough.
	private static final long DEFAULT_TIMEOUT_SECONDS = 120;
	private String catalog;
	private String schema;

	public InfluxDbConnection(String url, String username, String password, String database, String token,
		String timeout, InfluxDbDriver influxDbDriver) {
		OkHttpClient.Builder clientBuilder = timeoutConfiguredClientBuilder(timeout);
		// org.influxdb-java has no InfluxDBFactory overload that takes a token directly -
		// token auth only exists via a caller-supplied OkHttpClient.Builder with a custom
		// Authorization header interceptor, passed to connect(url, builder). Upstream
		// never used that overload - it always did Basic auth (or no auth at all), so
		// this plugin previously assumed token auth simply didn't work here at all - it
		// does, just not through Basic auth: sending a raw API token as the password with
		// Basic auth gets rejected, but "Authorization: Token <token>" is honored,
		// verified directly against a real InfluxDB 2.7.3 instance's InfluxQL endpoint. A
		// non-blank token always wins over username/password when both are somehow
		// supplied.
		if (token != null && !token.isBlank()) {
			clientBuilder.addInterceptor(chain -> chain.proceed(
				chain.request().newBuilder().header("Authorization", "Token " + token).build()));
			this.influxDbClient = InfluxDBFactory.connect(url, clientBuilder);
		} else if (username == null) {
			this.influxDbClient = InfluxDBFactory.connect(url, clientBuilder);
		} else {
			this.influxDbClient = InfluxDBFactory.connect(url, username, password, clientBuilder);
		}
		if (database != null) {
			this.getClient().setDatabase(database);
			// Upstream never set this.catalog here, only in the separate setCatalog(),
			// which SQuirreL happens not to call - getCatalog() returned null for the
			// whole session. GetTablesResultSet.defaultRetentionPolicy() needs a non-null
			// catalog to build "SHOW RETENTION POLICIES ON <db>" and report a table's
			// TABLE_SCHEM - SQuirreL's very first, automatic getTables() call (building
			// the object tree right after connecting) passes catalog=null, so that first
			// load always got TABLE_SCHEM=null again and the "TABLE" node came back
			// empty, exactly like before that fix, until the user right-clicked
			// "Refresh Item" (which re-issues getTables() with an explicit, non-null
			// catalog once a specific tree node is known). Recording the connection's own
			// database here means getCatalog() - and so that fallback - has a real answer
			// from the start, and the very first load already works.
			this.catalog = database;
		}

		this.influxDbMetadata = new InfluxDbMetadata(url, username, influxDbDriver, this);
		this.log = influxDbDriver.getParentLogger();
	}

	private static OkHttpClient.Builder timeoutConfiguredClientBuilder(String timeoutSecondsProperty) {
		long timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
		if (timeoutSecondsProperty != null && !timeoutSecondsProperty.isBlank()) {
			try {
				timeoutSeconds = Long.parseLong(timeoutSecondsProperty.trim());
			} catch (NumberFormatException ignored) {
				// keep the default rather than fail the whole connection over a typo'd
				// "timeout=" URL parameter
			}
		}
		return new OkHttpClient.Builder()
			.connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
			.readTimeout(timeoutSeconds, TimeUnit.SECONDS)
			.writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
			// OkHttp's default ConnectionPool keeps an idle HTTP connection around for
			// reuse for 5 minutes. SQuirreL's own connection pooling (SessionConnectionPool,
			// checked out per SQL script/Delete Records run and returned afterward)
			// checks out and returns its JDBC Connection - and therefore this same
			// InfluxDbClient/OkHttpClient - across many separate user actions that can be
			// minutes apart, so OkHttp routinely tries to reuse a TCP connection that has
			// sat idle since the last one. Fine on a direct connection; not fine through an
			// SSH port-forward (a common way to reach a remote InfluxDB, e.g.
			// "jdbc:influxdb:localhost:18086?..." tunneled to a real host) - the tunnel or a
			// NAT/firewall along the way can silently drop an idle forwarded connection
			// without either side seeing a clean FIN/RST, so OkHttp believes the pooled
			// connection is still good, sends the next request on it, and the read just
			// hangs until this client's own read timeout fires - indistinguishable from a
			// slow server. Verified directly: identical `DELETE FROM <table>` statements
			// through the same SQuirreL session, seconds apart, one succeeds in a few
			// seconds and the next times out - both instant when sent directly, not through
			// SQuirreL's pooled connection.
			//
			// An *unconditionally* fresh connection per request (0 idle connections kept)
			// was tried first and made a separate, pre-existing SQuirreL-core problem much
			// worse: SQuirreL's SQL editor calls this driver synchronously, on the EDT, once
			// per token, to syntax-highlight table names as you type
			// (RSyntaxHighlightTokenMatcher.isTable() -> SchemaInfo.loadColumns() ->
			// SQLDatabaseMetaData.getColumnInfo(), confirmed via a live thread dump) - for a
			// table whose columns haven't been background-loaded yet, "Load columns in
			// background" being on doesn't help, because syntax highlighting can't return an
			// answer before it has one. That's rare enough to live with when each such call
			// is a quick reused-connection round trip; forcing a full fresh connection (and,
			// through a laggy SSH tunnel, real handshake latency) on every single one of
			// those turned a barely-noticeable blip into the EDT visibly hanging for tens of
			// seconds. A short-lived pool - a couple of idle connections kept for only
			// several seconds - covers the common case this matters most (several rapid
			// calls in a row while typing) without resurrecting the original problem: an SSH
			// tunnel dropping a connection that's been idle for *minutes*, between separate
			// user actions, is a very different scenario from one that's been idle for single
			// digit seconds.
			.connectionPool(new okhttp3.ConnectionPool(2, 10, TimeUnit.SECONDS));
	}

	public InfluxDbStatement createStatement() {
		return new InfluxDbStatement(this, this.getClient());
	}

	public PreparedStatement prepareStatement(String sql) {
		return new InfluxDbPreparedStatement(this, sql, this.getClient());
	}

	public CallableStatement prepareCall(String sql) {
		return null;
	}

	public String nativeSQL(String sql) {
		String originalSql = sql;
		this.log.fine(() -> String.format("NativeSQL: %s", originalSql));
		if (KEEP_ALIVE_SQL_PATTERN.matcher(sql).matches()) {
			return "";
		} else {
			Matcher matcher = TABLE_ALIASES_SQL_PATTERN.matcher(sql);
			if (matcher.matches()) {
				String alias = matcher.group(5);
				String quotedAlias = Pattern.quote(alias);
				// Upstream only stripped "<alias>." when it was preceded by whitespace
				// (\s+<alias>\.), which covers "SELECT tbl.col FROM ... tbl WHERE
				// tbl.col=1" but not a comma-separated column list with no spaces, e.g.
				// "SELECT tbl.col1,tbl.col2,tbl.col3 FROM ... tbl" - only the first
				// "tbl." (preceded by the space after SELECT) ever got stripped. Every
				// later "tbl.colN" reference was sent to InfluxDB verbatim, which
				// InfluxDB treats as an unrecognized field name and returns as an
				// always-null column - this is exactly the query SQuirreL's Content tab
				// builds (ContentsTab.gatherColumnsForContentSelect), and it's what
				// caused the "data under the wrong header" bug: real InfluxDB, real
				// SQuirreL, real bar_stats table produced [time, atr20, null, null,
				// ...] instead of the requested 17 columns. Using a word boundary
				// instead of a whitespace requirement strips "tbl." after commas,
				// parens, or the start of the string too, not just after whitespace.
				sql = matcher.replaceFirst("SELECT $1 FROM $2$6")
					.replaceAll("\\b" + quotedAlias + "\\.", "")
					.replaceAll("\"" + quotedAlias + "\"\\.", "");
			}

			// SQuirreL's row delete/update feature (DataSetUpdateableTableModelImpl) always
			// builds "DELETE FROM <table's qualified name> WHERE ..." - the same
			// catalog.schema.table form that works fine for SELECT (and that
			// getTables()/getColumns() need to report so a user can pick a table
			// unambiguously with several databases open). InfluxQL's DELETE statement
			// doesn't accept that form at all: "DELETE FROM libra.autogen.fee WHERE ..."
			// fails to even parse ("retention policy not supported"), while the identical
			// WHERE clause against the bare measurement name works. Drop everything but the
			// final (measurement) segment for every DELETE FROM - InfluxQL's DELETE has no
			// way to target a specific retention policy either way, so there's nothing lost
			// dropping that part specifically. The catalog (database) part is a different
			// story - see extractDeleteDatabase() below for why it has to be preserved and
			// applied a different way, not just dropped along with the rest.
			//
			// This has to be a find-and-replace-every-occurrence, not a whole-string match
			// like the alias stripping above: SQuirreL's *other* delete entry point -
			// "Delete Records" on a table in the object tree, DeleteTablesCommand in
			// SQuirreL's own core - builds one script with a "DELETE FROM <qualified> ;"
			// line *per selected table* (no WHERE at all) and runs the whole thing as one
			// multi-statement script via SQLExecuterTask. A whole-string-match pattern
			// requiring nothing (or only a WHERE clause) after the table name doesn't match
			// that shape at all - not the trailing " ; " a single statement gets, and even
			// less a second "DELETE FROM ..." statement appended after it - so it silently
			// left every qualified name untouched. That version of this fix (matches() over
			// the whole string) never even attempted the rewrite for exactly the case that
			// mattered: every real "Delete Records" invocation. The qualified name reaching
			// InfluxDB unrewritten looked like a hang/timeout from SQuirreL's side rather
			// than the clear immediate parse error a single untouched DELETE gives.
			sql = DELETE_QUALIFIED_NAME_PATTERN.matcher(sql).replaceAll(matchResult -> {
				String qualifiedName = matchResult.group(1);
				String[] parts = qualifiedName.split("\\.");
				return "DELETE FROM " + parts[parts.length - 1];
			});

			// InfluxQL's parser rejects a whole list of ordinary words - "order" among them
			// - as a bare (unquoted) identifier: "DELETE FROM order" and "SELECT * FROM
			// libra_pairs.autogen.order" both fail with "found ORDER, expected identifier",
			// even though the exact same statement with the measurement name quoted
			// ("...FROM libra_pairs.autogen.\"order\"") works fine - verified directly
			// against the real server, for SELECT, DELETE, and SHOW alike. This driver never
			// quoted the measurement segment it builds into a FROM clause anywhere - not for
			// SELECT's qualified catalog.schema.table form (which upstream/InfluxDB itself
			// resolves natively, so this driver never had to touch it before), and not for
			// the bare name left behind by the DELETE stripping just above - so any table
			// whose name happens to collide with an InfluxQL keyword was completely
			// unreadable and undeletable through SQuirreL, with no indication why (a plain
			// parse error, not something that looked keyword-related at a glance). Quoting
			// is a no-op for a name that isn't a keyword (verified: identical results
			// quoted or not), so quote every FROM target uniformly rather than maintaining a
			// list of InfluxQL's reserved words to detect this only for. Runs after the
			// DELETE stripping above so it also covers DELETE's now-bare target, not just
			// SELECT/SHOW's.
			sql = FROM_UNQUOTED_TABLE_PATTERN.matcher(sql).replaceAll(matchResult ->
				"FROM " + matchResult.group(1) + "\"" + matchResult.group(2) + "\"");

			sql = this.convertSqlQuoting(sql);
			return sql;
		}
	}

	// The database segment nativeSQL() strips off a qualified DELETE isn't just
	// decoration the way the retention-policy segment is - it's the only thing that told
	// InfluxDB which database "bpbot_trade" even meant. Once stripped, the bare "DELETE
	// FROM bpbot_trade" this driver sends only ever targets whatever database this
	// *connection* has open by default (its JDBC URL's db=, or the last setCatalog()) -
	// completely independent of which catalog the qualified name in the original SQL
	// actually named. For a single-database connection that's a distinction without a
	// difference; for one that can see several databases at once (this plugin's token
	// driver routinely can, e.g. a broadly-scoped API token - see patch #18), it's a real
	// bug: a "Delete Records" run against libra_pairs.autogen.bpbot_trade, with the
	// connection's own default database set to libra, silently deleted the connection's
	// libra.bpbot_trade instead - no error (there's nothing wrong with "DELETE FROM
	// bpbot_trade" as a statement), because libra happens to have its own same-named
	// measurement. libra_pairs.bpbot_trade was never touched.
	//
	// InfluxDB's InfluxDBService takes the target database as a request parameter
	// entirely separate from the query text (org.influxdb.dto.Query has a constructor
	// for exactly this), so the fix isn't more text surgery - it's telling the HTTP
	// request itself which database to hit, overriding the connection's default for just
	// this one query. This extracts that database from the *original*, not-yet-rewritten
	// SQL text so InfluxDbStatement can build the Query with it. Multiple DELETE
	// statements in one combined "Delete Records" script are only unambiguous if they all
	// name the same database - InfluxDB's HTTP API has one db= per request, so a script
	// spanning multiple databases has no single correct request to send it as - in that
	// case this returns null and the delete falls back to the connection's default
	// database, same as before this fix (better than guessing wrong).
	public String extractDeleteDatabase(String sql) {
		if (sql == null) {
			return null;
		}
		Matcher matcher = DELETE_QUALIFIED_NAME_PATTERN.matcher(sql);
		String database = null;
		while (matcher.find()) {
			String[] parts = matcher.group(1).split("\\.");
			if (parts.length < 3) {
				continue;
			}
			if (database == null) {
				database = parts[0];
			} else if (!database.equals(parts[0])) {
				return null;
			}
		}
		return database;
	}

	private String convertSqlQuoting(String sql) {
		char[] chars = sql.toCharArray();
		StringBuilder result = new StringBuilder();
		AtomicInteger counter = new AtomicInteger(0);
		AtomicBoolean order = new AtomicBoolean(false);
		AtomicBoolean escape = new AtomicBoolean(false);

		for (char ch : chars) {
			escape.set(false);
			if (ch == '"' && !escape.get()) {
				counter.incrementAndGet();
				escape.set(false);
			} else {
				this.handleQuotes(result, counter, order);
				result.append(ch);
				escape.set(ch == '\\');
			}
		}

		this.handleQuotes(result, counter, order);
		return result.toString();
	}

	private void handleQuotes(StringBuilder result, AtomicInteger counter, AtomicBoolean order) {
		if (counter.get() > 0) {
			boolean odd = counter.get() % 2 != 0;
			boolean b = order.getAndSet(!order.get());
			if (odd && !b) {
				result.append("\"");
			}

			result.append("\\\"".repeat(counter.get() / 2));
			if (odd && b) {
				result.append("\"");
			}

			counter.set(0);
		}
	}

	public void setAutoCommit(boolean autoCommit) {
		if (!autoCommit) {
			throw new UnsupportedOperationException("Transactions are not supported. Autocommit must be true.");
		}
	}

	public boolean getAutoCommit() {
		return true;
	}

	public void commit() {
		throw new UnsupportedOperationException();
	}

	public void rollback() {
		throw new UnsupportedOperationException("Transactions are not supported. Can not rollback.");
	}

	public void close() {
		this.getClient().close();
		this.isClosed = true;
	}

	public boolean isClosed() {
		return this.isClosed;
	}

	public InfluxDbMetadata getMetaData() {
		return this.influxDbMetadata;
	}

	public void setReadOnly(boolean readOnly) {
		if (!readOnly) {
			throw new UnsupportedOperationException("Currently only readonly access to the InfluxDB is supported.");
		}
	}

	public boolean isReadOnly() {
		return true;
	}

	public void setCatalog(String catalog) {
		if (catalog != null) {
			this.influxDbClient.setDatabase(catalog);
			this.catalog = catalog;
		}
	}

	public void setTransactionIsolation(int level) {
		if (level != 0) {
			throw new UnsupportedOperationException("Transactions are not supported. Can set transaction isolation.");
		}
	}

	public int getTransactionIsolation() {
		return 0;
	}

	public SQLWarning getWarnings() {
		return null;
	}

	public void clearWarnings() {
		throw new UnsupportedOperationException("Clear warning is not supported.");
	}

	public Statement createStatement(int resultSetType, int resultSetConcurrency) {
		return new InfluxDbStatement(this, this.getClient());
	}

	public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) {
		return new InfluxDbPreparedStatement(this, sql, this.getClient());
	}

	public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) {
		return null;
	}

	public Map getTypeMap() {
		return new HashMap();
	}

	public void setTypeMap(Map map) {
		throw new UnsupportedOperationException("Type maps are not supported.");
	}

	public void setHoldability(int holdability) {
		throw new UnsupportedOperationException("Result set holdability is not supported.");
	}

	public int getHoldability() {
		return 0;
	}

	public Savepoint setSavepoint() {
		return null;
	}

	public Savepoint setSavepoint(String name) {
		return null;
	}

	public void rollback(Savepoint savepoint) {
		throw new UnsupportedOperationException("Transactions are not supported. Can not rollback.");
	}

	public void releaseSavepoint(Savepoint savepoint) {
		throw new UnsupportedOperationException("Transactions are not supported. Savepoint can not be released.");
	}

	public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) {
		return new InfluxDbStatement(this, this.getClient());
	}

	public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) {
		return new InfluxDbPreparedStatement(this, sql, this.getClient());
	}

	public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) {
		return null;
	}

	public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) {
		return new InfluxDbPreparedStatement(this, sql, this.getClient());
	}

	public PreparedStatement prepareStatement(String sql, int[] columnIndexes) {
		return new InfluxDbPreparedStatement(this, sql, this.getClient());
	}

	public PreparedStatement prepareStatement(String sql, String[] columnNames) {
		return new InfluxDbPreparedStatement(this, sql, this.getClient());
	}

	public Clob createClob() {
		return null;
	}

	public Blob createBlob() {
		return null;
	}

	public NClob createNClob() {
		return null;
	}

	public SQLXML createSQLXML() {
		return null;
	}

	public boolean isValid(int timeout) {
		Pong pong = this.getClient().ping();
		return pong.isGood() && pong.getResponseTime() < (long) timeout * 1000L;
	}

	public void setClientInfo(String name, String value) {
		throw new UnsupportedOperationException("Client info is not supported.");
	}

	public void setClientInfo(Properties properties) {
		throw new UnsupportedOperationException("Client info is not supported.");
	}

	public String getClientInfo(String name) {
		return null;
	}

	public Properties getClientInfo() {
		return new Properties();
	}

	public Array createArrayOf(String typeName, Object[] elements) {
		return null;
	}

	public Struct createStruct(String typeName, Object[] attributes) {
		return null;
	}

	public void setSchema(String schema) {
		this.influxDbClient.setRetentionPolicy(schema);
		this.schema = schema;
	}

	public void abort(Executor executor) {
		throw new UnsupportedOperationException("Aborting is not supported.");
	}

	public void setNetworkTimeout(Executor executor, int milliseconds) {
		throw new UnsupportedOperationException("Network timeout is not supported.");
	}

	public int getNetworkTimeout() {
		return 0;
	}

	public Object unwrap(Class iface) {
		return null;
	}

	public boolean isWrapperFor(Class iface) {
		return false;
	}

	public InfluxDB getClient() {
		return this.influxDbClient;
	}

	public String getCatalog() {
		return this.catalog;
	}

	public String getSchema() {
		return this.schema;
	}
}
