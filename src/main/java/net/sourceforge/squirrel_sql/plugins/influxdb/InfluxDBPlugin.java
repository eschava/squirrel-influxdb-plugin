package net.sourceforge.squirrel_sql.plugins.influxdb;

import net.sourceforge.squirrel_sql.client.plugin.DefaultSessionPlugin;
import net.sourceforge.squirrel_sql.client.plugin.PluginException;
import net.sourceforge.squirrel_sql.client.plugin.PluginSessionCallback;
import net.sourceforge.squirrel_sql.client.plugin.PluginSessionCallbackAdaptor;
import net.sourceforge.squirrel_sql.client.session.ISession;
import net.sourceforge.squirrel_sql.fw.persist.ValidationException;

/**
 * Adds InfluxDB (1.x and 2.x via the InfluxQL compatibility API) support to SQuirreL.
 * On load, registers a ready-to-use "InfluxDB (InfluxQL)" JDBC driver definition that
 * points at the driver jar bundled with this plugin - see
 * {@link InfluxDBDriverRegistrar}.
 */
public class InfluxDBPlugin extends DefaultSessionPlugin {

	@Override
	public String getInternalName() {
		return "influxdb";
	}

	@Override
	public String getDescriptiveName() {
		return "InfluxDB Plugin";
	}

	@Override
	public String getAuthor() {
		return "eschava";
	}

	@Override
	public String getVersion() {
		return "1.0.0";
	}

	@Override
	public void initialize() throws PluginException {
		super.initialize();
		try {
			InfluxDBDriverRegistrar.ensureRegistered(getApplication(), getPluginJarFilePath());
		} catch (ValidationException e) {
			throw new PluginException(e);
		}
	}

	@Override
	protected boolean isPluginSession(ISession session) {
		return session != null
			&& session.getDriver() != null
			&& InfluxDBDriverRegistrar.DRIVER_CLASS_NAME.equals(session.getDriver().getDriverClassName());
	}

	@Override
	public PluginSessionCallback sessionStarted(ISession session) {
		return new PluginSessionCallbackAdaptor();
	}
}
