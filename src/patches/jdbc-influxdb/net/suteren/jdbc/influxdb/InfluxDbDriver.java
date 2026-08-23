package net.suteren.jdbc.influxdb;

import java.net.MalformedURLException;
import java.net.URL;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

import net.suteren.jdbc.Version;

// Patched copy of net.suteren.jdbc.influxdb:influxdb-jdbc:0.2.6's InfluxDbDriver
// (Apache-2.0) - see pom.xml's patch-influxdb-driver execution and connect() below for
// the actual change. Upstream only ever builds an InfluxDbConnection from
// username/password - there's no way to reach org.influxdb-java's own token-auth
// capability (a custom OkHttpClient.Builder with an Authorization: Token interceptor -
// InfluxDBFactory has no dedicated "connect with token" overload, in this or any later
// released version). parseUrlParams() already captures every "?key=value" from the URL
// into a generic Properties map, so a "token" query parameter was already reaching this
// method - it just never got read. Verified directly against a real InfluxDB 2.7.3
// instance: "Authorization: Token <token>" on the InfluxQL /query endpoint returns real
// data, while Basic auth with an arbitrary username and the token as password (the
// thing that doesn't work, and what "log in with a token" usually means if you only
// have query/edit-alias fields to work with) gets rejected.
public class InfluxDbDriver implements Driver {
	private static final Logger log = Logger.getLogger(InfluxDbDriver.class.getName());
	public static final String USERNAME_PROPERTY = "username";
	public static final String PASSWORD_PROPERTY = "password";
	public static final String DATABASE_PROPERTY = "database";
	public static final String DB_PROPERTY = "db";
	public static final String USER_PROPERTY = "user";
	public static final String TOKEN_PROPERTY = "token";
	// Optional connect/read/write timeout override, in seconds - see
	// InfluxDbConnection.DEFAULT_TIMEOUT_SECONDS for why this exists.
	public static final String TIMEOUT_PROPERTY = "timeout";
	public static final Pattern URL_PATTERN = Pattern.compile("jdbc:influxdb:(.*)");

	public InfluxDbConnection connect(String url, Properties info) throws SQLException {
		Matcher m = URL_PATTERN.matcher(url);
		if (m.matches()) {
			String influxDbUrl = m.group(1);
			influxDbUrl = influxDbUrl.matches("^https?://.*$") ? influxDbUrl : "http://" + influxDbUrl;
			if (info == null) {
				info = parseUrlParams(influxDbUrl);
			} else {
				info.putAll(parseUrlParams(influxDbUrl));
			}

			return new InfluxDbConnection(influxDbUrl, info.getProperty("username", info.getProperty("user")),
				info.getProperty("password"), info.getProperty("database", info.getProperty("db")),
				info.getProperty(TOKEN_PROPERTY), info.getProperty(TIMEOUT_PROPERTY), this);
		} else {
			throw new SQLException(String.format("Invalid URL %s", url));
		}
	}

	private static Properties parseUrlParams(String url) throws SQLException {
		try {
			URL url1 = new URL(url);
			String[] ui = Optional.ofNullable(url1.getUserInfo())
				.map(u -> u.split(":", 2))
				.orElse(null);
			Map<String, String> properties = Optional.ofNullable(url1.getQuery()).stream()
				.flatMap(s -> Arrays.stream(s.split("&")))
				.map(x -> x.split("=", 2))
				.collect(Collectors.groupingBy(x -> x[0], Collectors.mapping(x -> x[1], Collectors.joining(","))));
			if (ui != null && ui.length > 0 && StringUtils.isNotBlank(ui[0])) {
				properties.put("username", ui[0]);
			}

			if (ui != null && ui.length > 1 && StringUtils.isNotBlank(ui[1])) {
				properties.put("password", ui[1]);
			}

			Properties properties1 = new Properties();
			properties1.putAll(properties);
			return properties1;
		} catch (MalformedURLException e) {
			throw new SQLException(String.format("Invalid URL %s", url), e);
		}
	}

	public boolean acceptsURL(String url) {
		return url != null && url.startsWith("jdbc:influxdb:");
	}

	public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
		Set<DriverPropertyInfo> propertyInfos = new HashSet<>();
		info.putAll(parseUrlParams(url));
		if (StringUtils.isBlank(info.getProperty("database", info.getProperty("db")))) {
			propertyInfos.add(makePropertyInfo("database", info.getProperty("database", info.getProperty("db")), true,
				"Database name"));
		}

		if (StringUtils.isBlank(info.getProperty("username")) && StringUtils.isBlank(info.getProperty("user"))) {
			propertyInfos.add(makePropertyInfo("username", info.getProperty("username"), false, "User name"));
		}

		if (StringUtils.isBlank(info.getProperty("password"))) {
			propertyInfos.add(makePropertyInfo("password", info.getProperty("password"), false, "Password"));
		}

		return propertyInfos.toArray(new DriverPropertyInfo[0]);
	}

	private static DriverPropertyInfo makePropertyInfo(String databaseProperty, String value, boolean required,
		String description) {
		DriverPropertyInfo driverPropertyInfo = new DriverPropertyInfo(databaseProperty, value);
		driverPropertyInfo.required = required;
		driverPropertyInfo.description = description;
		return driverPropertyInfo;
	}

	public int getMajorVersion() {
		return Version.getVersion().getMajor();
	}

	public int getMinorVersion() {
		return Version.getVersion().getMajor();
	}

	public boolean jdbcCompliant() {
		return false;
	}

	public Logger getParentLogger() {
		return log;
	}
}
