package net.sourceforge.squirrel_sql.plugins.influxdb;

import java.io.File;
import java.util.List;

import net.sourceforge.squirrel_sql.client.IApplication;
import net.sourceforge.squirrel_sql.client.gui.db.AliasesAndDriversManager;
import net.sourceforge.squirrel_sql.client.util.IdentifierFactory;
import net.sourceforge.squirrel_sql.fw.id.IIdentifier;
import net.sourceforge.squirrel_sql.fw.persist.ValidationException;
import net.sourceforge.squirrel_sql.fw.sql.ISQLDriver;
import net.sourceforge.squirrel_sql.fw.sql.SQLDriver;

/**
 * Registers two ready-to-use driver definitions with SQuirreL on first plugin load,
 * both pointing at the InfluxQL JDBC driver jar bundled next to this plugin's own jar
 * (see the {@code influxdb/lib/} folder in the plugin distribution):
 * <ul>
 * <li>{@code InfluxDB (InfluxQL)} - username/password (v1-compat) auth. Verified
 * against a real InfluxDB 2.7.3 OSS instance: InfluxDB's v1 compatibility API rejects
 * Basic auth unless the username is a real v1-compatible user created with
 * {@code influx v1 auth create} - an arbitrary placeholder username plus a raw 2.x API
 * token as the password does NOT work, regardless of what the token's own permissions
 * are.</li>
 * <li>{@code InfluxDB (Token)} - a real 2.x API token, embedded in the URL's
 * {@code token=} parameter. The underlying {@code org.influxdb-java} client has no
 * dedicated "connect with token" method, but does support a caller-supplied
 * {@code OkHttpClient.Builder}, which this plugin's patched driver uses to attach an
 * {@code Authorization: Token <token>} header - verified directly against the same
 * instance: that header is honored on InfluxQL queries even though Basic-auth-with-a-
 * token above is not. Leave the alias's User/Password fields blank when using this
 * driver; the token in the URL is all that's used. Prefer a token scoped to just the
 * bucket(s) you need (a raw API token created via the InfluxDB UI or
 * {@code influx auth create} can be broader than the v1-compat credentials above, up
 * to full admin access - narrow its permissions the same way you would any other
 * credential you're about to paste into a desktop SQL client).</li>
 * </ul>
 * Idempotent: does nothing for a driver definition that's already registered, so
 * re-loading the plugin (or upgrading it) never creates duplicate entries.
 */
public final class InfluxDBDriverRegistrar {

	public static final String DRIVER_CLASS_NAME = "net.suteren.jdbc.influxdb.InfluxDbDriver";

	static final String DRIVER_NAME = "InfluxDB (InfluxQL)";
	static final String TOKEN_DRIVER_NAME = "InfluxDB (Token)";

	// No "//" after the sub-protocol colon - net.suteren.jdbc.influxdb.InfluxDbDriver strips
	// "jdbc:influxdb:" and, if what's left doesn't already start with http(s)://, blindly
	// prepends "http://" to it. A leading "//" here would turn into "http:////host...".
	static final String URL_TEMPLATE = "jdbc:influxdb:<host>:<port>?db=<database>";
	static final String TOKEN_URL_TEMPLATE = "jdbc:influxdb:<host>:<port>?db=<database>&token=<token>";

	static final String WEBSITE_URL = "https://github.com/konikvranik/jdbc-influxdb";

	// Relative to the folder that contains this plugin's own jar file.
	private static final String BUNDLED_DRIVER_JAR_RELATIVE_PATH = "influxdb/lib/influxdb-jdbc-0.2.6.jar";

	private InfluxDBDriverRegistrar() {
	}

	/**
	 * @param pluginJarFilePath this plugin's own jar path, as returned by
	 *                          {@link net.sourceforge.squirrel_sql.client.plugin.IPlugin#getPluginJarFilePath()}
	 */
	public static void ensureRegistered(IApplication app, String pluginJarFilePath) throws ValidationException {
		AliasesAndDriversManager manager = app.getAliasesAndDriversManager();
		String bundledDriverJarPath = resolveBundledDriverJarPath(pluginJarFilePath);

		if (!isAlreadyRegistered(manager, DRIVER_NAME)) {
			registerDriver(manager, app, DRIVER_NAME, URL_TEMPLATE, bundledDriverJarPath);
		}
		if (!isAlreadyRegistered(manager, TOKEN_DRIVER_NAME)) {
			registerDriver(manager, app, TOKEN_DRIVER_NAME, TOKEN_URL_TEMPLATE, bundledDriverJarPath);
		}
	}

	private static void registerDriver(AliasesAndDriversManager manager, IApplication app, String name, String urlTemplate,
		String bundledDriverJarPath) throws ValidationException {
		IIdentifier identifier = IdentifierFactory.getInstance().createIdentifier();
		ISQLDriver driver = manager.createDriver(identifier);
		driver.setName(name);
		driver.setDriverClassName(DRIVER_CLASS_NAME);
		driver.setUrl(urlTemplate);
		driver.setWebSiteUrl(WEBSITE_URL);
		driver.setJarFileNames(new String[] { bundledDriverJarPath });

		manager.addDriver(driver, app.getMessageHandler());
	}

	static boolean isAlreadyRegistered(AliasesAndDriversManager manager, String name) {
		List<SQLDriver> drivers = manager.getDriverList();
		if (drivers == null) {
			return false;
		}
		for (SQLDriver driver : drivers) {
			if (DRIVER_CLASS_NAME.equals(driver.getDriverClassName()) && name.equals(driver.getName())) {
				return true;
			}
		}
		return false;
	}

	static String resolveBundledDriverJarPath(String pluginJarFilePath) {
		File pluginJarDir = new File(pluginJarFilePath).getParentFile();
		return new File(pluginJarDir, BUNDLED_DRIVER_JAR_RELATIVE_PATH).getAbsolutePath();
	}
}
