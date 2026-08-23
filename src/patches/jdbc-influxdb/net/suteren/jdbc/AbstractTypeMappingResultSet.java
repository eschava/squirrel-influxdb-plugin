package net.suteren.jdbc;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.Map;
import java.util.Optional;

// Patched copy of net.suteren.jdbc.AbstractTypeMappingResultSet (Apache-2.0) - see
// pom.xml's patch-influxdb-driver execution and getObject(int, Class) below for the
// actual change.
public abstract class AbstractTypeMappingResultSet extends AbstractBaseResultSet {
	private boolean wasNull;

	public Object getObject(int columnIndex, Class type) throws SQLException {
		Object object = this.getObject(columnIndex);
		if (object == null) {
			this.wasNull = true;
			return null;
		} else {
			this.wasNull = false;
			if (type.isInstance(object)) {
				return object;
			} else if (type == String.class) {
				return String.valueOf(object);
			} else if (type == Boolean.class) {
				return Boolean.valueOf(String.valueOf(object));
			} else if (type == Byte.class) {
				return object instanceof Number ? ((Number) object).byteValue() : Byte.valueOf(String.valueOf(object));
			} else if (type == Short.class) {
				return object instanceof Number ? ((Number) object).shortValue() : Short.valueOf(String.valueOf(object));
			} else if (type == Integer.class) {
				// Upstream did Integer.valueOf(String.valueOf(object)), which throws
				// NumberFormatException whenever the underlying value is a
				// decimal-formatted number (InfluxDB's JSON responses deserialize every
				// number as a Double, so e.g. a COUNT(*) result of 25489 comes back as
				// the string "25489.0" - not valid Integer.valueOf() input). Read
				// through Number's own accessor (truncating, same as any other JDBC
				// driver's int-from-decimal conversion) when the value already is one,
				// instead of round-tripping through a string format that doesn't match
				// what Integer.valueOf() accepts.
				return object instanceof Number ? ((Number) object).intValue() : Integer.valueOf(String.valueOf(object));
			} else if (type == Long.class) {
				return object instanceof Number ? ((Number) object).longValue() : Long.valueOf(String.valueOf(object));
			} else if (type == Float.class) {
				return object instanceof Number ? ((Number) object).floatValue() : Float.valueOf(String.valueOf(object));
			} else if (type == Double.class) {
				return object instanceof Number ? ((Number) object).doubleValue() : Double.valueOf(String.valueOf(object));
			} else if (type == byte[].class) {
				return String.valueOf(object).getBytes();
			} else if (type == Date.class) {
				return Date.valueOf(String.valueOf(object));
			} else if (type == Time.class) {
				return Time.valueOf(String.valueOf(object));
			} else if (type == Timestamp.class) {
				return Timestamp.valueOf(String.valueOf(object));
			} else if (type == InputStream.class) {
				return new ByteArrayInputStream(String.valueOf(object).getBytes());
			} else if (type == BigDecimal.class) {
				return BigDecimal.valueOf(Double.parseDouble(String.valueOf(object)));
			} else if (type == Ref.class) {
				return null;
			} else if (type == Blob.class) {
				return null;
			} else if (type == Clob.class) {
				return null;
			} else if (type == URL.class) {
				return null;
			} else if (type == RowId.class) {
				return null;
			} else if (type == Array.class) {
				return null;
			} else if (type == NClob.class) {
				return null;
			} else if (type == SQLXML.class) {
				return null;
			} else {
				return type == Reader.class ? null : object;
			}
		}
	}

	public boolean wasNull() throws SQLException {
		return this.wasNull;
	}

	public Object getObject(int columnIndex, Map map) throws SQLException {
		Class type = (Class) map.get(this.getMetaData().getColumnTypeName(columnIndex));
		return type == null ? this.getObject(columnIndex) : this.getObject(columnIndex, type);
	}

	public String getString(int columnIndex) throws SQLException {
		return (String) this.getObject(columnIndex, String.class);
	}

	public boolean getBoolean(int columnIndex) throws SQLException {
		return (Boolean) Optional.ofNullable((Boolean) this.getObject(columnIndex, Boolean.class)).orElse(false);
	}

	public byte getByte(int columnIndex) throws SQLException {
		return (Byte) Optional.ofNullable((Byte) this.getObject(columnIndex, Byte.class)).orElse((byte) 0);
	}

	public short getShort(int columnIndex) throws SQLException {
		return (Short) Optional.ofNullable((Short) this.getObject(columnIndex, Short.class)).orElse(Short.valueOf((short) 0));
	}

	public int getInt(int columnIndex) throws SQLException {
		return (Integer) Optional.ofNullable((Integer) this.getObject(columnIndex, Integer.class)).orElse(0);
	}

	public long getLong(int columnIndex) throws SQLException {
		return (Long) Optional.ofNullable((Long) this.getObject(columnIndex, Long.class)).orElse(0L);
	}

	public float getFloat(int columnIndex) throws SQLException {
		return (Float) Optional.ofNullable((Float) this.getObject(columnIndex, Float.class)).orElse(0.0F);
	}

	public double getDouble(int columnIndex) throws SQLException {
		return (Double) Optional.ofNullable((Double) this.getObject(columnIndex, Double.class)).orElse(0.0);
	}

	public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {
		return (BigDecimal) this.getObject(columnIndex, BigDecimal.class);
	}

	public byte[] getBytes(int columnIndex) throws SQLException {
		return (byte[]) this.getObject(columnIndex, byte[].class);
	}

	public Date getDate(int columnIndex) throws SQLException {
		return (Date) this.getObject(columnIndex, Date.class);
	}

	public Time getTime(int columnIndex) throws SQLException {
		return (Time) this.getObject(columnIndex, Time.class);
	}

	public Timestamp getTimestamp(int columnIndex) throws SQLException {
		return (Timestamp) this.getObject(columnIndex, Timestamp.class);
	}

	public InputStream getAsciiStream(int columnIndex) throws SQLException {
		return (InputStream) this.getObject(columnIndex, InputStream.class);
	}

	public InputStream getUnicodeStream(int columnIndex) throws SQLException {
		return (InputStream) this.getObject(columnIndex, InputStream.class);
	}

	public InputStream getBinaryStream(int columnIndex) throws SQLException {
		return (InputStream) this.getObject(columnIndex, InputStream.class);
	}

	public Reader getCharacterStream(int columnIndex) throws SQLException {
		return (Reader) this.getObject(columnIndex, Reader.class);
	}

	public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
		return (BigDecimal) this.getObject(columnIndex, BigDecimal.class);
	}

	public Ref getRef(int columnIndex) throws SQLException {
		return (Ref) this.getObject(columnIndex, Ref.class);
	}

	public Blob getBlob(int columnIndex) throws SQLException {
		return (Blob) this.getObject(columnIndex, Blob.class);
	}

	public Clob getClob(int columnIndex) throws SQLException {
		return (Clob) this.getObject(columnIndex, Clob.class);
	}

	public Array getArray(int columnIndex) throws SQLException {
		return (Array) this.getObject(columnIndex, Array.class);
	}

	public Date getDate(int columnIndex, Calendar cal) throws SQLException {
		cal.setTimeInMillis(this.getDate(columnIndex).getTime());
		return new Date(cal.getTimeInMillis());
	}

	public Time getTime(int columnIndex, Calendar cal) throws SQLException {
		cal.setTimeInMillis(this.getTime(columnIndex).getTime());
		return new Time(cal.getTimeInMillis());
	}

	public Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException {
		cal.setTimeInMillis(this.getTimestamp(columnIndex).getTime());
		return new Timestamp(cal.getTimeInMillis());
	}

	public URL getURL(int columnIndex) throws SQLException {
		return (URL) this.getObject(columnIndex, URL.class);
	}

	public RowId getRowId(int columnIndex) throws SQLException {
		return (RowId) this.getObject(columnIndex, RowId.class);
	}

	public NClob getNClob(int columnIndex) throws SQLException {
		return (NClob) this.getObject(columnIndex, NClob.class);
	}

	public SQLXML getSQLXML(int columnIndex) throws SQLException {
		return (SQLXML) this.getObject(columnIndex, SQLXML.class);
	}

	public String getNString(int columnIndex) throws SQLException {
		return (String) this.getObject(columnIndex, String.class);
	}

	public Reader getNCharacterStream(int columnIndex) throws SQLException {
		return (Reader) this.getObject(columnIndex, Reader.class);
	}
}
