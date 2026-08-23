# SQuirreL SQL Client InfluxDB Plugin

[![Build](https://github.com/eschava/squirrel-influxdb-plugin/actions/workflows/build.yml/badge.svg)](https://github.com/eschava/squirrel-influxdb-plugin/actions/workflows/build.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

Adds InfluxDB support to the [SQuirreL SQL Client](https://squirrel-sql.sourceforge.io/), using InfluxQL
(InfluxDB 1.x native, InfluxDB 2.x via its v1 compatibility API).

On load, the plugin registers two ready-to-use JDBC driver entries - name, driver class, URL template and
the bundled driver jar are all filled in automatically for both, so you only need to create an alias and
connect:

- **InfluxDB (InfluxQL)** - username/password auth (real v1-compatible credentials - see **Connecting**
  below).
- **InfluxDB (Token)** - a real InfluxDB 2.x API token, via the URL's `token=` parameter.

It uses the third-party [`jdbc-influxdb`](https://github.com/konikvranik/jdbc-influxdb) driver
(`net.suteren.jdbc.influxdb:influxdb-jdbc`), which wraps `org.influxdb:influxdb-java`. That driver only
supports InfluxQL, not SQL. See its README for the full list of limitations.

The build also patches a few classes of that driver in place - see [`docs/PATCHES.md`](docs/PATCHES.md) for
the full list of bugs fixed and why.

## Requirements

- SQuirreL SQL Client 5.x
- Java 17+
- An InfluxDB 1.x server, or an InfluxDB 2.x server with the v1 compatibility API set up (see below)

## Installation

1. Build the plugin (see **Building** below), or grab a pre-built `influxdb-plugin-<version>.zip`.
2. Unzip it into your SQuirreL installation's `plugins` directory (the folder that already contains
   `dbcopy.jar`, `graph.jar`, etc. - on macOS this is
   `/Applications/SQuirreLSQL.app/Contents/MacOS/plugins`). You should end up with a top-level
   `influxdb.jar` plus an `influxdb/` folder next to the other plugin jars.
3. Restart SQuirreL SQL Client.
4. In **Global Preferences -> Session -> General**, tick **Load columns in background** - see the warning
   below, this isn't optional.
5. In **Drivers**, you should now see **InfluxDB (InfluxQL)** and **InfluxDB (Token)** already configured
   with their jar path filled in. Create a new alias using whichever one matches how you're authenticating
   (see **Connecting** below).

> [!WARNING]
> Tick the **global** preference **Load columns in background** (`Global Preferences -> Session ->
> General`), or SQuirreL can hang the entire application the first time you reference any table name in
> the SQL editor. Confirmed with two thread dumps of an actually-hung session, not a guess - and this is a
> SQuirreL-wide setting, not something this plugin can default for you.
>
> SQuirreL's SQL editor syntax-highlights table names as you type by calling `SchemaInfo.isTableExt()` for
> every token. The first time a given table name is seen, `SchemaInfo.loadColumns()` needs to fetch its
> columns - and with **Load columns in background** off (SQuirreL's default), it does that fetch
> **synchronously, directly on the Swing EDT** (`SchemaInfo.accessDbToLoadColumns()`, called straight from
> the highlighter). For this driver that's two sequential HTTP round trips
> (`SHOW FIELD KEYS`/`SHOW TAG KEYS`), which is slow enough on the UI thread to look and feel like a full
> hang - both thread dumps showed the EDT with 20+ CPU-seconds burned inside exactly that call chain
> (`RSyntaxHighlightTokenMatcher.isTable` -> `SchemaInfo.loadColumns` -> `SQLDatabaseMetaData.getColumnInfo`
> -> this driver's `GetColumnResultSet`), with the rest of the app completely unresponsive. It's not a
> deadlock, just the EDT never getting free to repaint or handle input until that one call returns.
> Enabling **Load columns in background** moves the fetch to a worker thread instead, so the UI stays
> responsive while it loads (a per-table load only ever happens once - `SchemaInfoCache` remembers it was
> already attempted).
>
> `jdbc-influxdb` itself always reports `Connection.isReadOnly() == true`; `setReadOnly(false)` throws.
> That's a fixed property of the connection, not a per-alias setting - ticking/unticking **Read Only** on
> the alias doesn't change what the driver actually allows or refuses. What it *does* affect is whether
> SQuirreL's own row-delete feature is available in the Content tab: real deletes are possible (patch #11
> below made `PreparedStatement` bound parameters actually work, which that feature depends on), but only
> succeed if the connected user/token actually has write permission in InfluxDB, and only for a WHERE
> clause InfluxQL's `DELETE` accepts in the first place - time and tags, never a field's value (see
> **Deleting rows** below).

## Connecting

URL format: `jdbc:influxdb:<host>:<port>?db=<database>` - same for both InfluxDB 1.x and 2.x once you have
working credentials (see below); only how you obtain those credentials differs.

Example: `jdbc:influxdb:localhost:8086?db=telegraf`

Add `&timeout=<seconds>` to raise the connect/read/write timeout beyond its 2-minute default (see patch #20
below) - useful for a table large enough that even a plain read takes a while, or before running "Delete
Records" (deletes an entire table, no `WHERE` - see **Deleting rows** below) on one.

### Username/password ("InfluxDB (InfluxQL)" driver)

Supply your username/password as the alias's user/password (or as `username`/`password` connection
properties) - the driver sends them as HTTP Basic auth.

**InfluxDB 1.x**: works out of the box against the standard `/query` endpoint on port 8086. Use your
regular 1.x username/password.

**InfluxDB 2.x**: InfluxDB 2.x does not speak InfluxQL natively - connections go through its **1.x
compatibility API**, which requires a **real v1-compatible username/password**, created with
`influx v1 auth create`:

```bash
influx v1 auth create --org <org> --username <username> --password <password> \
  --read-bucket <bucket-id>
```

Then use that `<username>`/`<password>` as the alias's user/password. A raw 2.x API token as the password
here, with any username, does **not** work - verified directly against a real InfluxDB 2.7.3 OSS instance:
Basic auth with an arbitrary username and a token as the password gets rejected with
`{"code":"unauthorized","message":"Unauthorized"}`. For a real API token, use the **InfluxDB (Token)**
driver instead (below) - it authenticates a completely different way.

### API token ("InfluxDB (Token)" driver)

URL format: `jdbc:influxdb:<host>:<port>?db=<database>&token=<token>` - leave the alias's User/Password
fields blank; the token in the URL is all that's used.

`org.influxdb-java` (the library this driver wraps) has no dedicated "connect with token" method in any
released version, but it does accept a caller-supplied `OkHttpClient.Builder`, which this plugin's patched
driver uses to attach an `Authorization: Token <token>` header. Verified directly against a real InfluxDB
2.7.3 OSS instance: that header *is* honored on the InfluxQL endpoint - it's specifically Basic-auth-with-
a-token-as-password (above) that doesn't work, not token auth in general.

Prefer a token scoped to just the bucket(s) you need over your admin token - a raw API token can carry
broader access than the v1-compat credentials above, up to full admin, so narrow it the same way you would
any other credential before pasting it into a desktop SQL client:

```bash
influx auth create --org <org> --read-bucket <bucket-id> --write-bucket <bucket-id>
```

`db=<database>` in the URL still needs to name a database/DBRP that maps to your bucket the same way it
does for the username/password driver - see the DBRP note below.

Each bucket normally already has an implicit 1:1 DBRP mapping (visible as a "virtual" entry in
`influx v1 dbrp list`), so `<database>` in the JDBC URL is usually just the bucket's name and no separate
`influx v1 dbrp create` step is needed - only run that if you want a `<database>` name that differs from
the bucket name.

The JDBC URL is the same either way: `jdbc:influxdb:<host>:<port>?db=<database>`.

## Deleting rows

Right-click a row in a table's Content tab (with the tab set to editable) and choose **Delete Rows** to
delete it. Three things all have to be true for this to actually work:

1. **The connected user/token needs write permission** in InfluxDB. A v1-compat user created with
   `--read-bucket` only (see above) can't write; recreate it with `--write-bucket` too, or use a token
   scoped with write access.
2. **The row's WHERE clause has to be buildable from time and tags only.** SQuirreL builds a delete's
   `WHERE` clause from every column shown for a row by default, but InfluxQL's `DELETE` statement only
   accepts filtering by time and tags - never a field's value. Right-click the table in the object tree ->
   **Limit cell edit WHERE clause size** and move every field column to **Not Use Columns**, keeping only
   `time` and the table's tags in **Use Columns** (the **Use PK** button does this for you automatically -
   see patch #17 below for why that now works).
3. **The row actually has to be uniquely identified by that time+tags combination.** InfluxDB has no
   concept of a primary key stopping two points from sharing the exact same timestamp and tag values (with
   different field values) - if that happens for a given table, a delete built this way could remove more
   than the one row you selected. Rare in practice for typical time-series data, but worth knowing before
   deleting from a table you haven't checked.

If any of these isn't true, expect a clear error (not a silent no-op or the wrong rows disappearing) -
patch #15 below made sure of that.

**Deleting everything in a table** ("Delete Records" - right-click the table itself in the object tree,
not a row) only needs write permission (point 1 above) - it builds a plain `DELETE FROM <table>` with no
`WHERE` at all, so points 2 and 3 don't apply. It's normally fast (well under the default 2-minute
timeout) regardless of table size - InfluxQL's unconditional `DELETE` doesn't need to inspect matching
rows first, it just tombstones the whole series - so a timeout here almost always means either an old
plugin build (patches #14/#21/#22 below all address distinct causes of exactly this symptom) or a
connection to a database that's actually slow/unreachable, not the operation itself being large. If it
still happens on a current build, `&timeout=<seconds>` on the alias's URL (see **Connecting** above) is the
escape hatch either way.

## Building

```bash
./scripts/install-squirrel-core-jar.sh   # one-time: installs squirrel-sql.jar into ~/.m2
mvn package
```

Produces `target/plugin-dist/`, containing `influxdb.jar` and the companion `influxdb/` folder
(bundled driver jar + this README). Zip the contents of `plugin-dist/` for distribution, or use
`scripts/install-local.sh` to install straight into a local SQuirreL installation for testing.

## Bundled driver patches

Several classes of the bundled `jdbc-influxdb` 0.2.6 jar are patched in place at build time - plain copies
of the upstream (Apache-2.0) source under `src/patches/jdbc-influxdb/`, recompiled and jarred back into the
same class files right after `influxdb-jdbc` is fetched from Maven Central. None of them change what
queries the driver runs against real InfluxQL you type yourself - they only fix how it reports
errors/metadata, and how it handles the `%` wildcard SQuirreL itself sends when listing "every
table"/"every column".

25 patches in total, from a `NullPointerException` masking real errors on the very first query, to a
cross-database `DELETE` silently deleting from the wrong database. See
**[`docs/PATCHES.md`](docs/PATCHES.md)** for the full write-up of each one - symptom, root cause, fix, and
how it was verified.

## Known limitations

- No custom object-tree icons/labels for measurements/tags/fields - SQuirreL's generic JDBC metadata
  browser is used as-is (it already maps databases -> catalogs, measurements -> tables, fields/tags ->
  columns).
- Inherits the underlying `jdbc-influxdb` driver's core limitation: InfluxQL only, not SQL. Prepared
  statements and row deletes do work (see patches #11-#15 and **Deleting rows** below) as far as this
  plugin's own patches go, but are still bounded by what InfluxQL itself supports - no ordering by
  anything but `time`, and `DELETE`'s `WHERE` clause only ever accepts time and tags, never a field value.
- A database with more than one retention policy will only show measurements under its *default* RP's
  tree node (see patch #4 above) - InfluxQL can't report a more precise per-measurement RP from
  `SHOW MEASUREMENTS`.
- A *field/tag* name that collides with an InfluxQL keyword (e.g. a field named `duration`) or a tag *value*
  that looks like a number/duration literal (e.g. a value like `1CAT`, misparsed as an invalid duration) isn't
  quoted by SQuirreL's query builder in the column list it generates, so the Content tab's first query attempt
  fails to parse; it correctly falls back to plain `SELECT *`, which works but bypasses SQuirreL's usual
  column-selection logic. Not something this plugin can fix - quoting would need to happen in SQuirreL's own
  `ContentsTab.gatherColumnsForContentSelect`. (A *table/measurement* name colliding with a keyword, e.g.
  `order`, is fixed at the driver level - see patch #25.)
- Very wide/high-cardinality measurements can be slow enough to hit the driver's HTTP read timeout on the
  Content tab's first query attempt (seen once on a table with many numeric columns); it falls back to
  `SELECT *` the same way as the parse-error case above.
- SQuirreL's object tree only ever keeps one non-default catalog's table list "warm" at a time - navigating
  into (or refreshing) a different catalog than the one you were just looking at silently evicts the
  previously-loaded one, even the connection's own default catalog (patch #16), leaving its "TABLE" node
  looking empty until you right-click it and choose "Refresh Item" again. Verified this is unconditional on
  SQuirreL's side, not specific to any particular action: reproduced by simply expanding a second catalog's
  "TABLE" node, with no query, delete, or other operation involved at all. Most noticeable right after
  running "Delete Records" on a table in a non-default catalog (since doing that requires having just
  navigated into that catalog) - the delete itself is unaffected (see patch #23), but the *other* catalog's
  tree can look emptied out afterward purely as a side effect of the navigation that preceded it, not the
  delete. Not something this plugin can fix - SQuirreL's own `SchemaInfo`/object-tree caching only keeps one
  catalog's table list loaded at a time.
