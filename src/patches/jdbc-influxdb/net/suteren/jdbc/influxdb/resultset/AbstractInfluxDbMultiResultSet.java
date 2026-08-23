package net.suteren.jdbc.influxdb.resultset;

import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import net.suteren.jdbc.AbstractTypeMappingResultSet;

import org.influxdb.dto.QueryResult;

// Patched copy of net.suteren.jdbc.influxdb:influxdb-jdbc:0.2.6's
// AbstractInfluxDbMultiResultSet (Apache-2.0) - see pom.xml's patch-influxdb-driver
// execution and getMoreResults() below for the actual change. Kept fully generic
// (unlike upstream's own raw-typed style elsewhere in this driver) because this file
// is compiled together with every other patched source under src/patches/jdbc-influxdb
// in one javac invocation (see pom.xml) - callers like GetSchemaResultSet and
// InfluxDbResultSetMetaData rely on these methods' real generic signatures (e.g.
// List<QueryResult.Result>, not raw List) to type-check.
public abstract class AbstractInfluxDbMultiResultSet extends AbstractTypeMappingResultSet {
	private final List<QueryResult.Result> results;
	protected final AtomicInteger resultPosition = new AtomicInteger(0);
	protected final AtomicInteger seriesPosition = new AtomicInteger(0);
	protected final AtomicInteger rowPosition = new AtomicInteger(-1);
	private boolean isClosed = false;
	private String cursorName;
	protected Logger log;

	protected AbstractInfluxDbMultiResultSet(List<QueryResult.Result> results) {
		this.results = results;
	}

	public boolean getMoreResults() {
		if (this.getCurrentResult().map(QueryResult.Result::getSeries).map(List::size).filter((s) -> {
			return this.seriesPosition.intValue() + 1 < s;
		}).isPresent()) {
			this.seriesPosition.incrementAndGet();
			this.rowPosition.set(0);
			return true;
		// InfluxDB answers a successful bare "DELETE FROM <measurement>" (no WHERE) with an
		// empty HTTP body ("{}") rather than the usual {"results":[...]} envelope every other
		// statement gets - verified directly against the real server. That decodes to
		// QueryResult.getResults() == null, and this.results (set from it, in
		// InfluxDbStatement.executeQuery()) follows suit - every other method here already goes
		// through Optional.ofNullable(this.results) and so tolerates that (getCurrentRows()
		// safely resolves to List.of()), but this line called this.results.size() directly,
		// NPE-ing on the very first call. That silently broke every successful single-table
		// "Delete Records" run's SQLExecuterTask cleanup step: SQuirreL reported an error dialog
		// even though the delete itself had already gone through - this never surfaced before
		// because no "Delete Records" attempt had ever completed successfully far enough to
		// reach this line (always erroring or timing out first). Treat a null results list the
		// same as the empty one this falls through to for a single-result response - no more
		// results to walk to, either way.
		} else if (this.results != null && this.resultPosition.intValue() + 1 < this.results.size()) {
			this.resultPosition.incrementAndGet();
			this.seriesPosition.set(0);
			this.rowPosition.set(0);
			return true;
		} else {
			return false;
		}
	}

	public Optional<QueryResult.Result> getCurrentResult() {
		return Optional.ofNullable(this.results).map((r) -> {
			return r.get(this.resultPosition.intValue());
		});
	}

	public Optional<QueryResult.Series> getCurrentSeries() {
		return this.getCurrentResult().map(QueryResult.Result::getSeries).map((s) -> {
			return s.get(this.seriesPosition.intValue());
		});
	}

	public List<List<Object>> getCurrentRows() {
		return this.getCurrentSeries().map(QueryResult.Series::getValues).orElse(List.of());
	}

	public List<Object> getCurrentRow() {
		return this.getCurrentRows().get(this.rowPosition.get());
	}

	public boolean next() {
		this.log.fine("Next row.");
		if (this.rowPosition.intValue() < this.getCurrentRows().size()) {
			this.rowPosition.addAndGet(1);
		}

		return this.rowPosition.intValue() < this.getCurrentRows().size();
	}

	public boolean isBeforeFirst() {
		return this.rowPosition.get() < 0;
	}

	public boolean isAfterLast() {
		return this.rowPosition.intValue() >= this.getCurrentRows().size();
	}

	public boolean isFirst() {
		return this.rowPosition.get() == 0;
	}

	public boolean isLast() {
		return this.rowPosition.get() == this.getCurrentRows().size() - 1;
	}

	public void beforeFirst() {
		this.rowPosition.set(-1);
	}

	public void afterLast() {
		this.rowPosition.set(this.getCurrentRows().size());
	}

	public boolean first() {
		if (this.getCurrentRows().isEmpty()) {
			return false;
		} else {
			this.rowPosition.set(0);
			return true;
		}
	}

	public boolean last() {
		if (this.getCurrentRows().isEmpty()) {
			return false;
		} else {
			this.rowPosition.set(this.getCurrentRows().size() - 1);
			return true;
		}
	}

	public int getRow() {
		return this.rowPosition.intValue() + 1;
	}

	public boolean absolute(int row) {
		if (row < 0) {
			this.rowPosition.set(this.getCurrentRows().size() - row);
			return !this.isBeforeFirst();
		} else {
			this.rowPosition.set(row - 1);
			return !this.isAfterLast() && !this.isBeforeFirst();
		}
	}

	public boolean relative(int rows) {
		this.rowPosition.addAndGet(rows);
		return !this.isAfterLast() && !this.isBeforeFirst();
	}

	public boolean previous() {
		this.rowPosition.addAndGet(-1);
		return !this.isBeforeFirst();
	}

	public void setFetchDirection(int direction) {
	}

	public int getFetchDirection() {
		return 1002;
	}

	public void setFetchSize(int rows) {
	}

	public int getFetchSize() {
		return 0;
	}

	public int getType() {
		return 1004;
	}

	public int getConcurrency() {
		return 1007;
	}

	public void moveToInsertRow() {
	}

	public void moveToCurrentRow() {
	}

	public SQLWarning getWarnings() {
		return this.getCurrentResult().map(QueryResult.Result::getError).map(SQLWarning::new).orElse(null);
	}

	public void clearWarnings() {
	}

	public InfluxDbResultSetMetaData getMetaData() {
		return new InfluxDbResultSetMetaData(this);
	}

	public String getCursorName() {
		return this.cursorName;
	}

	public void close() {
		this.resultPosition.set(0);
		this.seriesPosition.set(0);
		this.rowPosition.set(-1);
		this.isClosed = true;
	}

	public void setCursorName(String name) {
		this.cursorName = name;
	}

	public int findColumn(String columnLabel) throws SQLException {
		return this.getCurrentSeries().map(QueryResult.Series::getColumns).map((c) -> {
			return c.indexOf(columnLabel.toLowerCase()) + 1;
		}).orElseThrow(() -> {
			return new SQLException(String.format("No column named %s", columnLabel));
		});
	}

	public boolean isClosed() {
		return this.isClosed;
	}

	public int getHoldability() {
		return 1;
	}

	public List<QueryResult.Result> getResults() {
		return this.results;
	}

	public AtomicInteger getResultPosition() {
		return this.resultPosition;
	}

	public AtomicInteger getSeriesPosition() {
		return this.seriesPosition;
	}

	public AtomicInteger getRowPosition() {
		return this.rowPosition;
	}
}
