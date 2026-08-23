package net.sourceforge.squirrel_sql.plugins.influxdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.sourceforge.squirrel_sql.client.IApplication;
import net.sourceforge.squirrel_sql.client.gui.db.AliasesAndDriversManager;
import net.sourceforge.squirrel_sql.fw.id.IIdentifier;
import net.sourceforge.squirrel_sql.fw.sql.SQLDriver;
import net.sourceforge.squirrel_sql.fw.util.IMessageHandler;

class InfluxDBDriverRegistrarTest {

	@Test
	void isAlreadyRegistered_falseWhenNoDriversMatch() throws Exception {
		AliasesAndDriversManager manager = mock(AliasesAndDriversManager.class);
		SQLDriver other = new SQLDriver();
		other.setDriverClassName("com.example.OtherDriver");
		when(manager.getDriverList()).thenReturn(List.of(other));

		assertFalse(InfluxDBDriverRegistrar.isAlreadyRegistered(manager, InfluxDBDriverRegistrar.DRIVER_NAME));
	}

	@Test
	void isAlreadyRegistered_trueWhenClassAndNameMatch() throws Exception {
		AliasesAndDriversManager manager = mock(AliasesAndDriversManager.class);
		SQLDriver influx = new SQLDriver();
		influx.setDriverClassName(InfluxDBDriverRegistrar.DRIVER_CLASS_NAME);
		influx.setName(InfluxDBDriverRegistrar.DRIVER_NAME);
		when(manager.getDriverList()).thenReturn(List.of(influx));

		assertTrue(InfluxDBDriverRegistrar.isAlreadyRegistered(manager, InfluxDBDriverRegistrar.DRIVER_NAME));
	}

	@Test
	void isAlreadyRegistered_falseWhenSameClassButDifferentName() throws Exception {
		// The two driver entries share a driver class (both are InfluxDbDriver) - only
		// the name tells them apart, so a token-driver entry existing must not make the
		// username/password entry (or vice versa) look already registered.
		AliasesAndDriversManager manager = mock(AliasesAndDriversManager.class);
		SQLDriver tokenDriver = new SQLDriver();
		tokenDriver.setDriverClassName(InfluxDBDriverRegistrar.DRIVER_CLASS_NAME);
		tokenDriver.setName(InfluxDBDriverRegistrar.TOKEN_DRIVER_NAME);
		when(manager.getDriverList()).thenReturn(List.of(tokenDriver));

		assertFalse(InfluxDBDriverRegistrar.isAlreadyRegistered(manager, InfluxDBDriverRegistrar.DRIVER_NAME));
	}

	@Test
	void isAlreadyRegistered_falseWhenListEmpty() {
		AliasesAndDriversManager manager = mock(AliasesAndDriversManager.class);
		when(manager.getDriverList()).thenReturn(Collections.emptyList());

		assertFalse(InfluxDBDriverRegistrar.isAlreadyRegistered(manager, InfluxDBDriverRegistrar.DRIVER_NAME));
	}

	@Test
	void resolveBundledDriverJarPath_isSiblingOfPluginJar() {
		String path = InfluxDBDriverRegistrar.resolveBundledDriverJarPath("/opt/squirrel/plugins/influxdb.jar");

		assertEquals("/opt/squirrel/plugins/influxdb/lib/influxdb-jdbc-0.2.6.jar", path);
	}

	@Test
	void ensureRegistered_doesNothingWhenBothAlreadyRegistered() throws Exception {
		IApplication app = mock(IApplication.class);
		AliasesAndDriversManager manager = mock(AliasesAndDriversManager.class);
		when(app.getAliasesAndDriversManager()).thenReturn(manager);

		SQLDriver existing = new SQLDriver();
		existing.setDriverClassName(InfluxDBDriverRegistrar.DRIVER_CLASS_NAME);
		existing.setName(InfluxDBDriverRegistrar.DRIVER_NAME);
		SQLDriver existingToken = new SQLDriver();
		existingToken.setDriverClassName(InfluxDBDriverRegistrar.DRIVER_CLASS_NAME);
		existingToken.setName(InfluxDBDriverRegistrar.TOKEN_DRIVER_NAME);
		when(manager.getDriverList()).thenReturn(List.of(existing, existingToken));

		InfluxDBDriverRegistrar.ensureRegistered(app, "/opt/squirrel/plugins/influxdb.jar");

		verify(manager, never()).createDriver(any());
		verify(manager, never()).addDriver(any(), any());
	}

	@Test
	void ensureRegistered_createsOnlyTheMissingDriver() throws Exception {
		IApplication app = mock(IApplication.class);
		AliasesAndDriversManager manager = mock(AliasesAndDriversManager.class);
		IMessageHandler messageHandler = mock(IMessageHandler.class);
		when(app.getAliasesAndDriversManager()).thenReturn(manager);
		when(app.getMessageHandler()).thenReturn(messageHandler);

		SQLDriver existing = new SQLDriver();
		existing.setDriverClassName(InfluxDBDriverRegistrar.DRIVER_CLASS_NAME);
		existing.setName(InfluxDBDriverRegistrar.DRIVER_NAME);
		when(manager.getDriverList()).thenReturn(List.of(existing));

		SQLDriver createdToken = new SQLDriver();
		when(manager.createDriver(any(IIdentifier.class))).thenReturn(createdToken);

		InfluxDBDriverRegistrar.ensureRegistered(app, "/opt/squirrel/plugins/influxdb.jar");

		assertEquals(InfluxDBDriverRegistrar.TOKEN_DRIVER_NAME, createdToken.getName());
		assertEquals(InfluxDBDriverRegistrar.DRIVER_CLASS_NAME, createdToken.getDriverClassName());
		assertEquals(InfluxDBDriverRegistrar.TOKEN_URL_TEMPLATE, createdToken.getUrl());
		verify(manager, times(1)).createDriver(any());
		verify(manager).addDriver(same(createdToken), same(messageHandler));
	}

	@Test
	void ensureRegistered_createsBothDriversWhenMissing() throws Exception {
		IApplication app = mock(IApplication.class);
		AliasesAndDriversManager manager = mock(AliasesAndDriversManager.class);
		IMessageHandler messageHandler = mock(IMessageHandler.class);
		when(app.getAliasesAndDriversManager()).thenReturn(manager);
		when(app.getMessageHandler()).thenReturn(messageHandler);
		when(manager.getDriverList()).thenReturn(Collections.emptyList());

		SQLDriver created = new SQLDriver();
		SQLDriver createdToken = new SQLDriver();
		when(manager.createDriver(any(IIdentifier.class))).thenReturn(created, createdToken);

		InfluxDBDriverRegistrar.ensureRegistered(app, "/opt/squirrel/plugins/influxdb.jar");

		assertEquals(InfluxDBDriverRegistrar.DRIVER_NAME, created.getName());
		assertEquals(InfluxDBDriverRegistrar.DRIVER_CLASS_NAME, created.getDriverClassName());
		assertEquals(InfluxDBDriverRegistrar.URL_TEMPLATE, created.getUrl());
		assertEquals("/opt/squirrel/plugins/influxdb/lib/influxdb-jdbc-0.2.6.jar", created.getJarFileNames()[0]);

		assertEquals(InfluxDBDriverRegistrar.TOKEN_DRIVER_NAME, createdToken.getName());
		assertEquals(InfluxDBDriverRegistrar.DRIVER_CLASS_NAME, createdToken.getDriverClassName());
		assertEquals(InfluxDBDriverRegistrar.TOKEN_URL_TEMPLATE, createdToken.getUrl());
		assertEquals("/opt/squirrel/plugins/influxdb/lib/influxdb-jdbc-0.2.6.jar", createdToken.getJarFileNames()[0]);

		verify(manager, times(2)).createDriver(any());
		verify(manager).addDriver(same(created), same(messageHandler));
		verify(manager).addDriver(same(createdToken), same(messageHandler));
	}
}
