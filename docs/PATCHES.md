# Bundled driver patches

The detailed engineering record of every bug found and fixed in the bundled InfluxQL JDBC driver - symptom,
root cause, fix, and how each was verified. See the main [README](../README.md) for installation and usage.


Several classes of the bundled `jdbc-influxdb` 0.2.6 jar are patched in place at build time. All are plain
copies of the upstream (Apache-2.0) source under `src/patches/jdbc-influxdb/`, recompiled and jarred back
into the same class files by `pom.xml`'s `patch-influxdb-driver` execution, right after `influxdb-jdbc` is
fetched from Maven Central. None of them change what queries the driver runs against real InfluxQL you
type yourself - they only fix how it reports errors/metadata, and how it handles the `%` wildcard SQuirreL
itself sends when listing "every table"/"every column".

### 1. `AbstractInfluxDbStatement.close()` NPE masking real errors

Upstream does:

```java
@Override public void close() {
	getResultSet().close();   // NPEs if the query never assigned resultSet
	closed = true;
}
```

`resultSet` is null whenever `executeQuery()` threw *before* assigning it - a malformed query, a request
timeout, anything. SQuirreL's `ContentsTab` (used for the object tree's "Content" tab, i.e. viewing a
table/measurement's data) always closes its statement in a `finally` block, so that `NullPointerException`
silently overwrites and hides whatever the real `SQLException` was. In practice this showed up as: opening
a table's Content tab reports `NullPointerException: ...getResultSet() is null` instead of the actual
error - e.g. `SELECT * FROM "balance"` timing out on a large measurement, or SQuirreL's own first-attempt
query (which qualifies wildcards as `<table>.*`) failing to parse as InfluxQL and only surfacing on the
NPE'd fallback attempt.

Fix: a null check before calling `close()` on it. This doesn't fix whatever the underlying query problem
is, it just makes the real error visible again instead of a misleading NPE.

### 2. `InfluxDbResultSetMetaData.getColumnType()` always returns `0`

Upstream hardcodes `getColumnType()` to return `0`, which isn't a valid `java.sql.Types` code. SQuirreL's
`CellComponentFactory.getGenericDataType()` switches on that code to pick a cell renderer/editor and has
no branch for `0`, so it silently returns `null`. The Content tab's column-gathering step
(`gatherColumnsForContentSelect`) then calls a method on that `null` and crashes with:

```
NullPointerException: ...IDataTypeComponent.getColumnForContentSelect(...) because the return value of
...CellComponentFactory.getDataTypeObject(...) is null
```

This happens on *every* table, once a JTable has displayed a query result using this metadata (its column
model is what `gatherColumnsForContentSelect` reads back on the next attempt/refresh).

Fix: infer a real SQL type (`BOOLEAN`/`DOUBLE`/`VARCHAR`) from the first row's actual value, the same way
upstream's own `getColumnClassName()` already does one line below it.

### 3. The `%` wildcard is sent to InfluxQL literally instead of meaning "no filter"

`DatabaseMetaData.getTables()`/`getColumns()`/`getIndexInfo()` take a `tableNamePattern` using SQL's `%`
wildcard convention; SQuirreL passes literal `%` to mean "every table" when populating the object tree.
Upstream drops that pattern straight into InfluxQL:

- `GetTablesResultSet` (backs the "TABLE" tree node): `SHOW MEASUREMENTS WITH MEASUREMENT =~ /%s/` - `%`
  is not a wildcard in InfluxQL's Go-flavored regex syntax, so `/%/ ` only matches a measurement whose name
  contains a literal `%` character. None do, so the "TABLE" node always came back empty.
- `AbstractBaseResultSet.getWithClause()` (backs field/tag lookups for columns and indices):
  `SHOW FIELD KEYS FROM "%"` - treated as one exact, nonexistent measurement named `%`, so it always came
  back empty too whenever a specific table wasn't given.

Verified directly: `SHOW MEASUREMENTS ON "libra" WITH MEASUREMENT =~ /%/` returns zero rows against a real
InfluxDB 2.7.3 instance with 18 real measurements; without the filter, all 18 come back.

Fix: both now treat a `%` pattern the same as no pattern at all (no `WITH MEASUREMENT`/`FROM` clause
appended), which is what "match everything" should mean. Concrete, non-wildcard patterns are untouched.

Note: `InfluxDbMetadata.getTables()`/`getColumns()`/`getIndexInfo()` already turn a literal `%` into `null`
before it reaches these methods, so in practice this specific bug is masked for every call that goes
through the standard `DatabaseMetaData` interface (which is everything SQuirreL calls). Fixed anyway since
it's a real, independently-reachable bug in these methods themselves - just not the one that was actually
blocking the "TABLE" node (see #4).

### 4. `GetTablesResultSet` always reports `TABLE_SCHEM = null`

This is the one that actually caused an empty "TABLE" node with *no* error anywhere, including after
pressing SQuirreL's "Refresh Object Tree and Database Meta Data Cache" (F5). SQuirreL's `SchemaInfo` loads
every table for a catalog once (`getTables(catalog, null, "%", null)`, cached in memory), then
`TableTypeExpander` filters that cache per tree node with
`schema.equalsIgnoreCase(tableInfo.getSchemaName())` whenever a specific schema is selected - here, a
retention policy, since `GetSchemaResultSet` reports each database's retention policies (e.g. `autogen`) as
its "schemas". A `null` `TABLE_SCHEM` never equals a non-null schema name, so *every* table got filtered
out of *every* retention-policy node, silently - no query fails, nothing gets logged, the folder is just
empty. F5 re-runs the same (still-empty-after-filtering) load, so it doesn't help either.

Fix: `GetTablesResultSet` now runs `SHOW RETENTION POLICIES ON "<catalog>"` and reports the database's
default retention policy as `TABLE_SCHEM` for every measurement, matching what `GetSchemaResultSet`
already reports as the schema name. This covers the common case of one retention policy per database
(also what `influx v1 dbrp create`, and InfluxDB's own auto-generated virtual DBRP mapping, already
assume). A measurement that only has data in a *non-default* retention policy still won't show up under
that RP's node - `SHOW MEASUREMENTS` has no per-RP scoping to report a more precise answer from.

Because this fix lives in compiled classes, not something SQuirreL re-queries, **only relaunching
SQuirreL picks it up - F5/Refresh alone will keep showing the stale empty result** from before the fix
was installed.

### 5. `firstRowValue()` (added by patch #2) could index past the current row

Patch #2's `getColumnType()`/`getColumnTypeName()` read `getCurrentRows().get(0).get(column - 1)` to infer
a type from the first row's value. `getCurrentRows()` reflects whichever statement/series the underlying
multi-result cursor currently happens to be positioned on - for `GetColumnResultSet`'s combined
`"SHOW FIELD KEYS ...; SHOW TAG KEYS ..."` query, that can be the 2-column FIELD KEYS series or the
1-column TAG KEYS series depending on where iteration currently is, but the `column` index passed in
assumes the wider (2-column) shape. Asking for column 2 while positioned on the 1-column series threw
`IndexOutOfBoundsException: Index 1 out of bounds for length 1`, breaking the Columns/Content tabs for any
table whose columns happened to be read while positioned on the narrower series.

Fix: bounds-check before indexing and return `null` (same fallback as the already-handled empty-`currentRows`
case, which resolves to `VARCHAR`) instead of throwing.

### 6. `AbstractProxyResultSet.next()`/`isAfterLast()` infinite loop when a statement has no series at all

The real cause of `getColumns()` hanging (returning an endless stream of `null` columns) for measurements
with fields but **no tags** - `startup_commands` in testing. `GetColumnResultSet`'s combined query runs
`SHOW FIELD KEYS ...; SHOW TAG KEYS ...` as two statements. A measurement with no tags gets back a `SHOW
TAG KEYS` response with no `"series"` key at all (`Result.getSeries()` is `null`). Upstream's
`isAfterLast()`/`isLast()` checked series-position with `.filter(s -> ...).isPresent()`, which is always
`false` when `getSeries()` is `null` - so once iteration reached that statement's result, `isAfterLast()`
could never become `true` again, and `next()` in `ResultSetReader`'s `while (rs.next())` loop never
terminated.

Fix: treat a missing series list as `0` series (`.orElse(0)` instead of `.filter(...).isPresent()`), so a
statement with no series at all correctly counts as fully exhausted instead of stalling the position check
forever.

### 7. `GetColumnResultSet` reports columns in a different order (and count) than `SELECT *` actually returns

Upstream's `DatabaseMetaData.getColumns()` lists "time" nowhere (it isn't a real field or tag - see #5's
description), then every field (in `SHOW FIELD KEYS` order), then every tag (in `SHOW TAG KEYS` order). A
real `SELECT * FROM table` returns a completely different shape: `time` first, then every field *and* tag
interleaved, sorted alphabetically together as one list. SQuirreL's Content tab builds its column headers
from `getColumns()` *before* running the query, then reads the query's actual result positionally against
those headers - so any mismatch in order or count shows up as data under the wrong header. In testing, a
table's `atr20` header showed nothing while the *next* header over showed `atr20`'s real value, and
everything past a certain column was blank - a full table's worth of fields silently shifted by however
many columns the two orderings diverged by.

Fix: `GetColumnResultSet` now merges the field-key and tag-key rows into one list, sorts them by name the
same way InfluxDB orders `SELECT *` columns, and adds a synthetic `time` row *first* (matching where a real
`SELECT *` always puts it, regardless of where "time" appears in an explicit column list - see patch #8) -
so `getColumns()`'s column list has the same order and count as what a real query actually returns.

One more shape mismatch surfaced while merging: `SHOW FIELD KEYS` rows are `[fieldKey, fieldType]` (2
elements), but `SHOW TAG KEYS` rows are just `[tagKey]` (1 element) - InfluxDB tags don't have a declared
type the way fields do. Merging them unpadded meant reading `TYPE_NAME` for any tag row threw
`IndexOutOfBoundsException: Index 1 out of bounds for length 1` - which is why this only broke tables that
*have* tags (nearly all of them) and not the couple that don't. Tag rows are now padded to
`[tagKey, "tag"]` before merging, so every row this metadata reports is safe to read uniformly. Verified
directly against all 18 real tables in the test database (0 to 217 columns each, with and without tags) -
`getColumns()` now reads cleanly for every one.

### 8. `InfluxDbConnection.nativeSQL()` only strips the table alias from the *first* column in a list

The real root cause of the column/header misalignment that patch #7 alone didn't fully fix. SQuirreL's
`ContentsTab` builds its first query attempt as one comma-separated list with no spaces:
`SELECT tbl.col1,tbl.col2,tbl.col3 FROM ... tbl`. Upstream's alias-stripping regex in `nativeSQL()` is
`\s+<alias>\.` (a literal whitespace character required immediately before `alias.`) - true for
`tbl.col1` (preceded by the space after `SELECT`), but every later `,tbl.colN` is preceded by a comma, not
whitespace, so the regex never matches it and `tbl.` stays in the query sent to InfluxDB. InfluxDB doesn't
understand table aliases; it treats `tbl.colN` as one unrecognized field name and quietly returns that
column with an always-`null` value instead of erroring. Verified directly:
`SELECT tbl.atr20,tbl.bbe20_s2_lower,...,tbl.time FROM ...` came back with `atr20` (the only unprefixed,
correctly-stripped reference) holding real data and every other requested column `null`, under its own
literal `tbl.colN` name - not the 17 real field/tag names `getColumns()` had reported.

Fix: replaced the whitespace-anchored regex with a word-boundary-anchored one (`\b<alias>\.`), so `tbl.` is
stripped wherever it starts a token - after whitespace, after a comma, or at the start of the string - not
just after whitespace. Verified end-to-end: `getColumns()`'s reported column list and a real query's actual
returned column list are now identical, in order, for every table tested.

With this fixed, the earlier "`time` first in an explicit column list returns zero rows" finding (patch #7)
turned out to be a symptom of *this same bug*, not a separate InfluxQL quirk: with only the first column's
alias ever stripped, a query with `time` first left every other column as an unresolvable `tbl.colN`
reference, which happened to make InfluxDB return nothing rather than nulls. With every alias correctly
stripped, `SELECT tbl.time,tbl.atr20,... FROM ... tbl` returns real rows in the exact order written - so
`time` is back to being the *first* row in `GetColumnResultSet`'s merged list (see patch #7), matching where
a real `SELECT *` always puts it. This also means the two now agree unconditionally: a table whose first
query attempt fails for an unrelated reason (see patch #9's `order`/`duration`/`1CAT` examples) and falls
back to plain `SELECT *` still lines up with `getColumns()`'s headers, instead of only doing so sometimes.

### 9. `GetColumnResultSet` reported every column as `Types.NUMERIC`, regardless of its real type

Upstream hardcodes `DATA_TYPE` (and `SOURCE_DATA_TYPE`) to `Types.NUMERIC` for every column `getColumns()`
reports - fields and tags alike, plus the synthetic `time` row added by patch #7. SQuirreL's Content tab
uses this value (via `CellComponentFactory`) to pick which cell type/renderer to read each column with
*before* running the query, independent of patch #2's `InfluxDbResultSetMetaData.getColumnType()` (which
only applies to a `ResultSet` already in hand). A string tag like `exchange` or `instrument`, or `time`
itself (an ISO-8601 timestamp string), got read with `DataTypeBigDecimal`, which calls
`Double.parseDouble()` on the raw string and throws:

```
NumberFormatException: For input string: "2026-07-10T11:56:30.098Z"
```

showing up as `<Error>` in the Content tab for exactly the non-numeric columns, on every row.

Fix: `DATA_TYPE`/`SOURCE_DATA_TYPE` are now derived from the real `fieldType` text `GetColumnResultSet`
already carries per row (`float`/`integer`/`boolean` -> the matching numeric/boolean JDBC type; `string`
and `tag` -> `Types.VARCHAR`), instead of a hardcoded constant. Verified directly: `getColumns()` now
reports `time`/`exchange`/`instrument`/`interval` as `VARCHAR` and every InfluxDB `float` field as
`DOUBLE`, and the Content tab reads a full table (`bar_stats`, 17 columns x 11 rows) with zero errors.

### 10. `Statement.setMaxRows()` was stored but never enforced

SQuirreL's "Limit rows" settings - Session Properties > Object Tree > "Contents - Limit rows" (100 by
default) for the Content tab, and the SQL tab's own "Limit Rows" field - both work the standard JDBC way:
call `Statement.setMaxRows(n)` before running the query and expect the driver to honor it.
`AbstractInfluxDbStatement` already had the field, getter and setter for this, but nothing ever read
`maxRows` back to actually limit anything - every Content tab request ran unbounded, e.g. reading all
438,672 rows of a table instead of the 100 already configured (and shown as pre-checked, since 100 is
SQuirreL's own default) in Session Properties.

Fix: `InfluxDbStatement.executeCommand()` now appends `LIMIT <maxRows>` to the native query when
`getMaxRows() > 0`, but only for a plain `SELECT` that doesn't already specify its own `LIMIT` - the `SHOW
...` metadata queries `GetColumnResultSet`/`GetTablesResultSet` run internally are left untouched, since
`LIMIT` doesn't apply to them the same way and they must always see every field/tag/measurement regardless
of the user's row-limit preference. With this fixed, SQuirreL's already-existing 100-row default just works
without any extra configuration - and if 100 isn't the right number for you, Session Properties > Object
Tree > "Contents - Limit rows" (or the SQL tab's own "Limit Rows" field) now actually controls it.

### 11. `InfluxDbPreparedStatement` never actually supported bound parameters

Upstream's entire `PreparedStatement` implementation was a stub: every `setXxx()` parameter setter threw
`UnsupportedOperationException`, `executeUpdate()` always returned `0`, `execute()` always returned `false`.
SQuirreL's row delete/update feature (`DataSetUpdateableTableModelImpl` in its own core) relies on exactly
this: before deleting a selected row, it runs a `PreparedStatement`-based `select count(*) from <table>
where col1=? and col2=? ...` safety check ("does this WHERE clause match exactly one row?"), so every
attempt to delete a row from the Content tab failed immediately with that `UnsupportedOperationException`
instead of even reaching the actual delete.

Fix: `InfluxDbPreparedStatement` now substitutes each bound parameter into the SQL text (formatted as an
InfluxQL literal - strings single-quoted and escaped, numbers/booleans raw, dates/timestamps as RFC3339
strings) in place of its `?`, then delegates to the same `executeQuery(String)`/`executeUpdate(String)`/
`execute(String)` every other statement already uses - InfluxQL has no server-side parameter binding to
delegate to instead.

### 12. Aggregate queries (`COUNT`, ...) report their value under a `time` column, not where SQuirreL expects it

InfluxQL always answers an aggregate query with `time` as its first column - normally the start of each
`GROUP BY time()` bucket, or a single epoch-0 placeholder row (`1970-01-01T00:00:00Z`) when there's no such
grouping. SQuirreL's row delete/update safety check (see #11) always runs a plain `select count(*) from
<table> <where clause>` with no `GROUP BY` and reads the result with `rs.getInt(1)`, assuming - like any
ordinary SQL database - that column 1 *is* the count. Here it's `time`, which fails to parse as an int and
turned "does this WHERE clause match exactly one row?" into a hard error before any delete/update could
even be attempted.

Fix: `InfluxDbStatement.executeCommand()` now strips the leading `time` column from a query's result
whenever the query is a `SELECT COUNT(...)` with no `GROUP BY` - the one case where that column is a
meaningless placeholder rather than real per-bucket data, which a grouped aggregate still needs to keep.

### 13. Numeric type coercion broke on InfluxDB's own number formatting

A closely related problem surfaced once #12 was fixed: `rs.getInt(1)` *still* failed, now with
`NumberFormatException: For input string: "25489.0"`. InfluxDB's JSON responses deserialize every number as
a `Double` regardless of its real type, so a count of `25489` arrives as the string `"25489.0"` -
`Integer.valueOf("25489.0")` (what `AbstractTypeMappingResultSet.getObject(int, Class)` did for every
numeric target type) rejects that format outright.

Fix: when the underlying value is already a `Number`, read it through that `Number`'s own accessor
(`.intValue()`, `.longValue()`, `.doubleValue()`, ...) instead of formatting it to a string and re-parsing -
avoids the round trip that broke, and is more robust in general (also sidesteps scientific-notation strings
for very large/small doubles that `Integer.valueOf`/`Long.valueOf` never accepted either).

### 14. InfluxQL's `DELETE` doesn't accept the qualified table name `SELECT` needs

With #11-#13 fixed, SQuirreL's delete confirmation ("Do you wish to delete 1 rows from this table?")
finally worked - but clicking "Yes" failed with `{"code":"invalid","message":"failed to parse query:
retention policy not supported..."}`. SQuirreL always builds its delete/update SQL from a table's *qualified*
name (`DELETE FROM libra.autogen.fee WHERE ...`) - the same `catalog.schema.table` form that works fine for
`SELECT`, and that this driver's own `getTables()`/`getColumns()` need to report so a user can pick a table
unambiguously. InfluxQL's `DELETE` statement doesn't accept that form at all - only a bare (optionally
quoted) measurement name - and it doesn't support scoping a delete to a specific retention policy either
way.

Fix: `InfluxDbConnection.nativeSQL()` now recognizes `DELETE FROM <anything>.<anything>.<measurement> WHERE
...` and rewrites it down to `DELETE FROM <measurement> WHERE ...` before sending it to InfluxDB - safe
here specifically because a connection is already scoped to one database (the JDBC URL's `db=`), and
InfluxQL's `DELETE` has no per-retention-policy targeting to lose by dropping the qualification.

(This fix only handled a single `DELETE FROM ... WHERE ...` covering the entire input string - correct for
SQuirreL's row-level delete, but not for its table-level "Delete Records" action, which builds something
shaped quite differently. See patch #21, which replaced this with a more general fix.)

### 15. A failed write was completely silent - no exception, not even a warning

The last thing standing between "SQuirreL asks to confirm a delete" and "the delete actually happens or
visibly fails" was permissions: the connected user only had read access, so the `DELETE` was rejected by
InfluxDB. It should have surfaced as an error - instead SQuirreL reported success. The reason:
`QueryResult.getError()` only reflects a *top-level* error; InfluxDB reports per-statement failures like
"insufficient permissions" inside each individual `Result`'s own `error` field instead, which upstream
never checked. A rejected write came back as a normal-looking, merely empty `QueryResult` - indistinguishable
from "ran fine, affected nothing." For a `SELECT` that's a curiosity; for a `DELETE`, silently doing nothing
while claiming success is actively dangerous.

Fix: `InfluxDbStatement.executeCommand()` now checks every `Result`'s own `error` field and throws
(surfacing as a real `SQLException`, exactly like a parse error already did) the moment any is set, instead
of silently discarding it. Verified end-to-end against a real permission-denied `DELETE`: it now fails with
a clear `insufficient permissions` message instead of reporting nothing changed.

### 16. The object tree's "TABLE" node needed a manual "Refresh Item" every single session

`GetTablesResultSet`'s `defaultRetentionPolicy()` (patch #4) needs an actual database name to query `SHOW
RETENTION POLICIES ON <db>` and report a table's `TABLE_SCHEM`. SQuirreL's very first, automatic
`getTables()` call after connecting - the one that populates the object tree - passes `catalog=null` ("no
filter", a normal JDBC convention), not the connected database's name. That first load therefore always got
`TABLE_SCHEM=null` again - the exact original "empty TABLE node" bug from patch #4 - and stayed that way
until the user right-clicked "Refresh Item" on a specific tree node, which re-issues `getTables()` with an
explicit, non-null catalog and so worked.

Fix: `InfluxDbConnection`'s constructor now also records its database into `this.catalog` (upstream only
set it via the separate `setCatalog()`, which SQuirreL never happens to call, so `getCatalog()` returned
`null` for the whole session) and `GetTablesResultSet` falls back to `InfluxDbConnection.getCatalog()`
whenever its caller passed a null/blank catalog. Verified directly: a simulated first call, `getTables(null,
null, "%", ...)`, now reports the correct `TABLE_SCHEM` for all 18 tables immediately - no refresh needed.

### 17. Several `DatabaseMetaData` methods returned `null` instead of an empty `ResultSet`, NPE-ing SQuirreL's per-table tabs

`getPrimaryKeys()`, `getBestRowIdentifier()`, `getImportedKeys()`, `getExportedKeys()`, `getVersionColumns()`,
`getTablePrivileges()`, and `getColumnPrivileges()` all just returned `null` unconditionally - upstream's
answer for "InfluxDB has nothing like this." A `null` isn't a valid `ResultSet` though, and SQuirreL's
"Primary Key"/"Row IDs"/"Exported Keys"/"Imported Keys"/"Privileges"/"Column Privileges" tabs all call these
directly and expect one back, even an empty one - `ResultSetWrapper.getResultSet()` returning that `null`
NPEs the instant `.getMetaData()` is called on it, which is the first thing every one of those tabs does.

Fix, in two parts:

- `getPrimaryKeys()` and `getBestRowIdentifier()` are now real. InfluxDB has no formal primary key
  constraint, but a point's *identity* - what two writes need to match on to be the same point rather than
  two different ones - is its timestamp plus its full set of tag values (never its fields, which is exactly
  what `DELETE`'s WHERE clause can't filter on either - see #14). Reporting `(time, tag1, tag2, ...)` as a
  table's primary key is the closest honest equivalent, and happens to be exactly the column set the "Edit
  'WHERE' columns" dialog's "Use PK" button needs to build a delete/update-safe WHERE clause automatically,
  instead of selecting them by hand per table.
- The rest (`getImportedKeys`, `getExportedKeys`, `getVersionColumns`, `getTablePrivileges`,
  `getColumnPrivileges`) now return a genuinely empty (`GetEmptyResultSet`: zero rows, correctly-shaped
  columns) `ResultSet` instead of `null` - InfluxDB really has no foreign keys, privilege grants, or row
  versioning, so "no rows" is the honest answer for these; "crash" wasn't. (One implementation trap along
  the way: an empty *Results list* still isn't safe - `AbstractInfluxDbMultiResultSet.getCurrentResult()`
  unconditionally indexes into it - so `GetEmptyResultSet` uses a single `Result` with no series, which the
  "no series" handling from patch #6 already treats correctly as zero rows.)

Verified directly: `getPrimaryKeys()`/`getBestRowIdentifier()` return `(time, tag1, tag2, ...)` in order for
every table tested, and none of the seven affected tabs throw anymore.

### 18. No way to authenticate with a real InfluxDB 2.x API token

An earlier version of this plugin tried "any username + a raw 2.x API token as the password" via Basic
auth and, after that was empirically disproven (real 401s against a real InfluxDB 2.7.3 instance), the
plugin and its docs concluded token auth just wasn't reachable through this driver at all. That conclusion
was wrong - it only tested one specific (non-working) way to send a token. `Authorization: Token <token>`,
checked directly against the same instance, *is* honored by the InfluxQL endpoint. The real gap: upstream's
`InfluxDbConnection` only ever calls `InfluxDBFactory.connect(url)` or `connect(url, username, password)` -
`org.influxdb-java` has no dedicated token-auth method, but does accept a caller-supplied
`OkHttpClient.Builder` (`connect(url, builder)`), which upstream never used.

Fix, across two files:

- `InfluxDbDriver.connect()` now also reads a `token` property - `parseUrlParams()` already captured every
  `?key=value` from the URL generically, so a `token=...` parameter was reaching this method already; it
  just never got read - and passes it through to `InfluxDbConnection`'s constructor (a new parameter).
- `InfluxDbConnection`'s constructor, given a non-blank token, builds an `OkHttpClient.Builder` with an
  interceptor that adds `Authorization: Token <token>` to every request, and calls
  `InfluxDBFactory.connect(url, builder)` instead of the username/password overload. A token always wins
  over username/password if both are somehow supplied.
- The plugin (`InfluxDBDriverRegistrar`) now registers a second driver entry, **InfluxDB (Token)**, with
  its own URL template (`...?db=<database>&token=<token>`), alongside the existing username/password one -
  see **Connecting** above.

Verified end-to-end against a real InfluxDB 2.7.3 instance and a real admin API token: connects, queries,
and lists all 18 tables correctly through SQuirreL's own `DatabaseMetaData` calls.

### 19. A schema name shared by multiple databases showed up as a duplicate tree node per database

Only visible with a connection that can see more than one database - the token driver's admin-scoped
tokens (#18) are the first time that came up in testing, since the username/password driver's v1-compat
credentials are normally scoped to exactly one bucket. Every InfluxDB database gets its own default
retention policy, almost always also named `autogen` - so a connection that can see several databases
(here: `_monitoring`, `_tasks`, `libra`, `libra_backtest`, all InfluxDB 2.x defaults/buckets) has several
different (database, `autogen`) pairs that all share that same schema name. Upstream's `getSchemas()` (the
catalog-less, "list every schema on this connection" form SQuirreL calls once to populate the whole tree)
reported one row per pair unconditionally - 4 rows, all named `autogen`, each tagged with its own real
database. SQuirreL's own `DatabaseExpander.createSchemaNodes()` has no deduplication of its own: for a
given catalog being expanded, it walks that entire list and adds a tree node for every row
`SchemaContainedInCatalogCheck.containedInCatalog()` says matches - with no "already added a node with this
name" check - so every database in the tree ended up with one duplicate `autogen` node per *other* database
that also happened to use that name, not just the one row that was actually about it.

Fix: `GetSchemaResultSet`'s catalog-less path now reports each distinct schema name only once - with its
real database if only one database actually has it, or with an empty `TABLE_CATALOG` if more than one
does. An empty `TABLE_CATALOG` isn't a workaround here - `containedInCatalog()` already treats it as "this
schema belongs to every catalog," which is the literally correct answer for a name that genuinely isn't
tied to one particular database. Verified directly: `getSchemas()` across a 4-database token connection now
returns exactly one `autogen` row instead of four, and the object tree shows exactly one `autogen` node
under each database.

### 20. Large *reads* timed out after 10 seconds, with no way to raise it

Every `InfluxDBFactory.connect(...)` call upstream used, an unconfigured `new OkHttpClient.Builder()`,
inherits OkHttp's own default connect/read/write timeout: 10 seconds. Fine for typical queries, but not
against a large enough table - a plain `SELECT *` against a 2.4-million-row measurement failed with
`java.net.SocketTimeoutException: Read timed out` even with the "Limit rows"/`LIMIT` fixes from patch #10
in place, because the *default* behavior with no limit configured is still an unbounded `SELECT *`.

(A `DELETE` with no `WHERE` on a large table - SQuirreL's "Delete Records" action - failing the exact same
way looked like the same problem at first, and this fix does still apply to it. It turned out not to be the
*real* reason that specific case failed, though - see patch #21.)

Fix: every connection now uses an explicitly-configured `OkHttpClient.Builder` with a 2-minute
connect/read/write timeout instead of OkHttp's 10-second default - applied to all three auth paths (no
auth, username/password, token). If 2 minutes still isn't enough for a particular operation, add
`timeout=<seconds>` to the JDBC URL to override it, e.g.
`jdbc:influxdb:<host>:<port>?db=<database>&timeout=300`. Verified directly: a table that previously failed
outright (`balance`, 2.4 million rows) now reads successfully, and the configured timeout for all three auth
paths was confirmed via reflection against the live `OkHttpClient` instance each one actually built.

### 21. "Delete Records" (table-level, no `WHERE`) still silently sent the qualified name patch #14 was supposed to strip

With the timeout raised to 2 minutes (patch #20), "Delete Records" on a table still failed the same way,
just after longer - `java.net.SocketTimeoutException` - which looked like the same "large table, genuinely
slow" story as the `SELECT *` case above. It wasn't: tested directly against the real table this was first
reported on (a 2-row measurement on a remote server, `libra_pairs.adjustment`) - a raw, driver-independent
`DELETE FROM adjustment` (bare name) against that same server completed in under 2 seconds. The actual
`DELETE FROM libra_pairs.autogen.adjustment` (still fully qualified) was reaching InfluxDB completely
unrewritten, despite patch #14 - so it hit the exact same "retention policy not supported" parse failure as
before that fix, just manifesting as a timeout this time instead of the immediate error patch #14 was
verified against.

The reason patch #14's fix didn't fire here: it required `Matcher.matches()` against the *entire* input -
correct for SQuirreL's row-level delete (`DataSetUpdateableTableModelImpl`), which really does send exactly
one `DELETE FROM <qualified> WHERE ...` and nothing else. "Delete Records" is a different SQuirreL feature
entirely (`DeleteTablesCommand`, triggered by right-clicking a table - or several - in the object tree, not
a row in its Content tab), and it builds SQL differently: one `DELETE FROM <qualified> ;` line *per selected
table*, no `WHERE` at all, concatenated into a single multi-statement script and run as one call via
SQuirreL's generic `SQLExecuterTask`. That trailing `" ; \n"` - and, with more than one table selected, a
second `DELETE FROM ...` appended right after it - doesn't match "nothing, or a `WHERE` clause" at the end
of the string, so the whole pattern failed to match and the rewrite silently never applied. Two real bugs
matching in every earlier test (which only ever exercised the row-level, `WHERE`-clause path) and missing
this one entirely.

Fix: replaced the whole-string `matches()` check with a `find()`-based one that locates and rewrites every
`DELETE FROM <qualified.name>` occurrence in the text, wherever it appears and whatever follows it -
handles a bare `DELETE FROM x`, one with a `WHERE` clause, and any number of them concatenated into one
script, uniformly. Verified directly: `nativeSQL()` now correctly strips both a single `DeleteTablesCommand`-
style statement and a two-table combined script, and the identical delete that previously timed out
completes in under 2 seconds against a real remote server.

### 22. A reused pooled connection could silently be dead, especially through an SSH tunnel

With #21 fixed, "Delete Records" worked - once. The exact same action on a different (small, 569-row)
table minutes later, in the same still-open SQuirreL session, failed with the same
`SocketTimeoutException` - and, tested directly, that table's `DELETE` was just as fast (~2s) as the one
that had just worked. The difference wasn't the query, the table, or the server - it was time: SQuirreL
checks a `Connection` (and so this driver's one `OkHttpClient`) out of its own `SessionConnectionPool` for
each SQL script or "Delete Records" run and returns it afterward, so the same `OkHttpClient` - and its
*own* internal HTTP connection pool - gets reused across user actions that can be minutes apart. OkHttp's
default pool keeps an idle connection around for up to 5 minutes hoping to reuse it. Fine on a direct
connection to the database; not fine when the URL points at an SSH port-forward (a common way to reach a
remote InfluxDB - `jdbc:influxdb:localhost:<forwarded-port>?...`) or anything else in between that can
silently drop an idle forwarded connection without a clean close on either end. OkHttp has no way to know
the connection it's about to reuse is already dead - it sends the request, and the read just hangs until
this driver's own timeout (patch #20) eventually fires. Indistinguishable from a slow server from the
outside, which is why this looked like the same "large operation" story for two separate reasons in a row.

Fix: disable OkHttp's idle connection reuse entirely at first (`ConnectionPool(0, ...)` - keep zero idle
connections), so every request opens a fresh connection instead of gambling on a pooled one that might
already be dead. Verified directly: four requests through the same connection, 15 seconds apart
(`idleConnectionCount()` confirmed 0 throughout), all completed in under a second each against a real
SSH-tunneled server - the exact pattern (same connection, real gaps between uses) that reproduced the
timeout before this fix.

That unconditional version made a separate, pre-existing SQuirreL-core problem much worse, though: the SQL
editor calls this driver synchronously, *on the EDT*, once per token, to syntax-highlight table names as you
type (`RSyntaxHighlightTokenMatcher.isTable()` -> `SchemaInfo.loadColumns()` ->
`SQLDatabaseMetaData.getColumnInfo()`, confirmed via a live `jstack` thread dump) for any table whose columns
haven't been background-loaded yet - "Load columns in background" being on doesn't help, since syntax
highlighting can't return an answer before it has one. That's rare enough to live with when each such call
is a quick reused-connection round trip; forcing a full fresh connection - real handshake latency, through a
laggy SSH tunnel - on every single one of those turned a barely-noticeable blip into the EDT visibly hanging
for tens of seconds while typing a query. Settled on a short-lived pool instead - a couple of idle
connections, kept for only several seconds (`ConnectionPool(2, 10, TimeUnit.SECONDS)`) - which covers the
case that matters most (several rapid calls in a row while typing) without resurrecting the original
problem: an SSH tunnel dropping a connection that's been idle for *minutes*, between separate user actions,
is a very different scenario from one that's been idle for single-digit seconds. Verified both halves stayed
fixed: the original minutes-apart-timeout repro still completed cleanly, and typing in the SQL editor no
longer visibly hung.

### 23. "Delete Records" could silently delete from the wrong database

With #21 and #22 fixed, "Delete Records" reliably ran without error - but on a connection that can see
several databases at once (the token-auth driver from patch #18, with an API token scoped across more than
one database), it could report success while leaving the table the user actually selected completely
untouched, and instead delete same-named data out of a *different* database on the same server. Reported
against a real connection whose default database (`?db=libra`) differed from the database the target table
actually lived in (`libra_pairs`): "Delete Records" on `libra_pairs.bpbot_trade` returned no error, but
`SELECT COUNT(*)` against `libra_pairs.bpbot_trade` afterward still showed every row present. Root cause:
patch #21's fix strips a qualified `DELETE FROM db.rp.measurement` down to the bare measurement name
(InfluxQL's `DELETE` rejects the qualified form) - correctly, but that also throws away the *only* signal of
which database the delete was supposed to target. The resulting bare `DELETE FROM bpbot_trade` request
executes against whatever database this *connection* has open by default, completely independent of which
database the original, qualified SQL named - silently correct only when the two happen to be the same
database, and silently wrong whenever they're not, with no error either way since there's nothing invalid
about `DELETE FROM bpbot_trade` as a statement on its own. `bpbot_trade` happening to exist as a real
measurement in both `libra` and `libra_pairs` is exactly why this looked like "it ran, but did nothing" -
data left the wrong database, not nowhere.

Fix: `InfluxDbConnection.extractDeleteDatabase()` recovers the database segment from the *original*,
not-yet-rewritten SQL before `nativeSQL()` strips it, and `InfluxDbStatement.executeCommand()` now passes it
to `org.influxdb.dto.Query`'s two-argument constructor (`Query(command, database)`), which targets that
specific database for just this one request - independent of, and overriding, the connection's own default.
A combined multi-statement script (`DeleteTablesCommand` builds one `DELETE FROM <qualified> ;` line per
selected table) only has one unambiguous database to send the request as if every statement in it names the
same one; if they disagree, extraction returns `null` and the request falls back to the connection's default
database, same as before this fix - no worse than the pre-fix behavior, just no longer silently wrong for
the common single-database-script case. Verified directly against the real server: seeded a dedicated test
measurement (not a real user table) with different values in both `libra` and `libra_pairs`, ran the same
`DeleteTablesCommand`-shaped statement (`DELETE FROM libra_pairs.autogen.<test> ; \n`) that reaches this
driver through a connection whose default database is `libra`, and confirmed afterward that `libra_pairs`'s
row was gone while `libra`'s identically-named row was untouched - then reversed, confirming a bare
unqualified `DELETE` still uses the connection's default database exactly as before.

### 24. Fixing #23 exposed a `NullPointerException` on every successful single-table "Delete Records"

With #23 fixed and deleting from the right database, "Delete Records" against a single table now hit a
*different* error: SQuirreL popped a `NullPointerException` dialog ("Cannot invoke
`java.util.List.size()` because `this.results` is null") - even though, checked directly, the delete had
already gone through correctly. Root cause: InfluxDB answers a successful bare `DELETE FROM <measurement>`
(no `WHERE`) with an empty HTTP body (`{}`), not the `{"results":[...]}` envelope every other statement
gets - verified directly against the real server. That decodes to `QueryResult.getResults() == null`, and
`AbstractInfluxDbMultiResultSet`'s `results` field (set from it) follows suit. Every other method that
class has for reading `results` already goes through `Optional.ofNullable(this.results)` and so tolerates
null just fine (`getCurrentRows()` safely resolves to an empty list) - except `getMoreResults()`, which
called `this.results.size()` directly with no null check, and NPE'd on the very first call.
`DeleteTablesCommand` calls exactly that, once, right after running its script. This bug has likely existed
since patch #21 first made bare (unqualified) `DELETE FROM <table>` statements possible - it just never
surfaced before now, because no "Delete Records" attempt had ever completed successfully far enough to
reach this line (always erroring out on the wrong database, or timing out, first).

Fix: `AbstractInfluxDbMultiResultSet` is now also part of this project's patch set (previously only
`InfluxDbConnection` and `InfluxDbStatement` were patched) - `getMoreResults()` treats a null `results` list
the same as every other method here already does: no more results to walk to, same as an empty one. Verified
directly: the same `DeleteTablesCommand`-shaped statement that reproduced the NPE now runs
`stmt.execute(...)` followed by `stmt.getMoreResults()` without error, through both a direct JDBC repro and
the real SQuirreL GUI - "Delete Records" now completes with no error dialog (at most SQuirreL's own benign
"query succeeded with no error message" `SQLWarning`, unrelated to this bug), while still correctly deleting
from the intended database and leaving every other database untouched.

### 25. A table named after an InfluxQL keyword (e.g. `order`) couldn't be read or deleted at all

Reported: a table named `order` failed to open its Content tab ("Failed to read table contents") and
failed its Row Count tab too. The actual queries SQuirreL sent (from the log): `select tbl.time,...
from libra_pairs.autogen.order tbl`, `select * from libra_pairs.autogen.order tbl`, `select count(*)
from libra_pairs.autogen.order` - all failing with `{"code":"invalid","message":"failed to parse query:
found ORDER, expected identifier..."}`. `order` is one of InfluxQL's reserved words (`ORDER BY`); InfluxQL
requires a keyword-colliding identifier to be double-quoted (`"order"`) to be parsed as a plain name instead
- verified directly against the real server, both for the qualified form SQuirreL sends
(`libra_pairs.autogen."order"`) and the bare form. This driver never quoted the measurement segment of a
`FROM` clause anywhere: not for `SELECT`'s qualified `catalog.schema.table` form (InfluxDB already resolves
that natively, so this driver never previously had to touch it) and not for the bare name patch #21 leaves
behind after stripping a qualified `DELETE FROM` down to just the measurement - so *any* table whose name
happened to collide with an InfluxQL keyword was completely unreadable and undeletable through SQuirreL,
with nothing in the error to suggest why.

Fix: `nativeSQL()` now quotes the final (measurement) segment of every `FROM <name>` it sees - qualified or
bare, `SELECT`/`SHOW`/the now-bare `DELETE` alike - unless it's already quoted. Quoting is a no-op for a name
that isn't a keyword (verified: identical results and row counts quoted or not, for both `SELECT` and
`DELETE`, against real tables), so this quotes every `FROM` target uniformly rather than trying to maintain
a list of InfluxQL's reserved words to detect this only for. Verified directly: `select * from
libra_pairs.autogen.order tbl` (SQuirreL's exact fallback query) and the equivalent `DeleteTablesCommand`
shape (`DELETE FROM libra_pairs.autogen.order ; \n`, tested against a dedicated `"select"`-named test
measurement so as not to touch real data) both now succeed - as does every other table's Content tab,
confirming the blanket quoting doesn't change behavior for ordinary names.

