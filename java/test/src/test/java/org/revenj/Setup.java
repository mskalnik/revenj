package org.revenj;

import com.dslplatform.compiler.client.CompileParameter;
import com.dslplatform.compiler.client.Context;
import com.dslplatform.compiler.client.Main;
import com.dslplatform.compiler.client.parameters.*;
import gen.model.Boot;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.revenj.extensibility.Container;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;

public abstract class Setup {
	private static class TestContext extends Context {

		StringBuilder error = new StringBuilder();

		public void show(String... values) {
		}

		public void log(String value) {
		}

		public void log(char[] value, int len) {
		}

		public void warning(String value) {
			error.append(value);
		}

		public void warning(Exception ex) {
			error.append(ex.getMessage());
		}

		public void error(String value) {
			error.append(value);
		}

		public void error(Exception ex) {
			error.append(ex.getMessage());
		}
	}

	private static EmbeddedPostgres postgres;
	public static String getUrl() {
		return "jdbc:postgresql://localhost:" + postgres.getPort() + "/revenj?user=postgres&password=postgres";
	}

	@BeforeClass
	public static void setupDatabase() throws IOException, SQLException {
		postgres = database();
	}

	@AfterClass
	public static void teardownDatabase() throws IOException {
		if (postgres != null) {
			postgres.close();
			postgres = null;
		}
	}

	protected Container container;

	@Before
	public void initContainer() throws IOException {
		container = Setup.container();
	}

	@After
	public void closeContainer() throws Exception {
		container.close();
	}

	public static EmbeddedPostgres database() throws IOException, SQLException {
		EmbeddedPostgres postgres = EmbeddedPostgres.builder()
				.setPort(0)
				.start();

        try (Connection connection = DriverManager.getConnection("jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres", "postgres", "postgres")) {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("CREATE DATABASE revenj ENCODING 'utf8' TEMPLATE template1");
            }
        }

		TestContext context = new TestContext();
		context.put(Download.INSTANCE, null);
		context.put(Force.INSTANCE, null);
		context.put(ApplyMigration.INSTANCE, null);
		context.put(DisablePrompt.INSTANCE, null);
		context.put(PostgresConnection.INSTANCE, "localhost:" + postgres.getPort() + "/revenj?user=postgres&password=postgres");
		context.put(DslPath.INSTANCE, "src/test/resources");
		List<CompileParameter> params = Main.initializeParameters(context, ".");
		if (!Main.processContext(context, params)) {
			try {
				Thread.sleep(2000);
			} catch (InterruptedException e) {
				throw new IOException(e);
			}
			context.error.setLength(0);
			if (!Main.processContext(context, params)) {
				throw new IOException("Unable to migrate database: " + context.error.toString());
			}
		}
		return postgres;
	}

	public static Container container() throws IOException {
		return (Container) Boot.configure(getUrl());
	}

	public static Container container(Properties properties) throws IOException {
		return (Container) Boot.configure(getUrl(), properties);
	}

}
