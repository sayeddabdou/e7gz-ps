package ps.e7gz.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.images.builder.Transferable;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.RepoFiles;

/**
 * Runs the physical model's own test scripts ({@code docs/04-erd/physical/test_*_v127.sh}) against a database
 * built by OUR migrations (V1 + security baseline), so the DB contract is checked on every build (E7-10).
 *
 * <p>The scripts run unchanged inside the PostgreSQL container; only their connection ({@code -h /tmp -p 5499})
 * is pointed at the container's socket. Each script gets its own fresh copy of the migrated database, as the
 * change report requires. Every {@code PASS}/{@code FAIL} line becomes one JUnit test.
 */
class DatabaseContractTest {

    private static final String TEMPLATE_DB = "e7gz_contract";
    private static final String SCRIPTS_DIR = "docs/04-erd/physical";
    private static final List<String> SCRIPTS =
            List.of("test_behaviour_v127.sh", "test_tournament_v127.sh", "test_privileges_v127.sh");

    private static final Pattern RESULT = Pattern.compile("RESULT: (\\d+) passed, (\\d+) failed");
    private static final Pattern CHECK = Pattern.compile("^(PASS|FAIL) {2}(.*?)(?: {2}:: (.*))?$");

    private static PostgreSQLContainer<?> db;

    @BeforeAll
    static void migrateTemplateDatabase() throws Exception {
        db = PostgresTestContainer.get();
        psql("postgres", "DROP DATABASE IF EXISTS " + TEMPLATE_DB);
        psql("postgres", "CREATE DATABASE " + TEMPLATE_DB + " TEMPLATE template0"); // empty, like e7gz_ps
        // Same migrations and Flyway settings as the application (application.yml, spring.flyway.*).
        Flyway.configure()
                .dataSource(PostgresTestContainer.jdbcUrl(TEMPLATE_DB), MIGRATOR, MIGRATOR)
                .initSql("SET ROLE postgres")
                .defaultSchema("flyway")
                .createSchemas(true)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @TestFactory
    Stream<DynamicNode> databaseRulesBehaveAsDocumented() throws Exception {
        List<DynamicNode> suites = new ArrayList<>();
        for (String script : SCRIPTS) {
            suites.add(DynamicContainer.dynamicContainer(script, runScript(script)));
        }
        return suites.stream();
    }

    private static List<DynamicNode> runScript(String script) throws Exception {
        String copy = "t_" + script.replace(".sh", "");
        psql("postgres", "DROP DATABASE IF EXISTS " + copy);
        psql("postgres", "CREATE DATABASE " + copy + " TEMPLATE " + TEMPLATE_DB);

        String body = Files.readString(RepoFiles.get(SCRIPTS_DIR + "/" + script), StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace("-h /tmp -p 5499", "-h /var/run/postgresql -p 5432");
        String path = "/tmp/db-tests/" + script;
        db.copyFileToContainer(Transferable.of(body), path);
        ExecResult run = db.execInContainer("bash", path, copy);
        String output = run.getStdout();

        List<DynamicNode> checks = new ArrayList<>();
        for (String line : output.split("\n")) {
            Matcher m = CHECK.matcher(line.stripTrailing());
            if (m.matches()) {
                boolean passed = m.group(1).equals("PASS");
                String reason = m.group(3);
                checks.add(DynamicTest.dynamicTest(m.group(2), () -> {
                    if (!passed) {
                        fail(reason == null ? "failed" : reason);
                    }
                }));
            }
        }
        Matcher result = RESULT.matcher(output);
        checks.add(DynamicTest.dynamicTest("script completed", () -> {
            assertThat(result.find())
                    .as("%s did not print its RESULT line.%nstdout:%n%s%nstderr:%n%s", script, output, run.getStderr())
                    .isTrue();
            assertThat(Integer.parseInt(result.group(2))).as("failed checks").isZero();
            assertThat(Integer.parseInt(result.group(1))).as("passed checks").isPositive();
        }));
        return checks;
    }

    private static void psql(String database, String sql) throws IOException, InterruptedException {
        ExecResult r = db.execInContainer("psql", "-U", "postgres", "-d", database, "-v", "ON_ERROR_STOP=1", "-c", sql);
        if (r.getExitCode() != 0) {
            throw new IllegalStateException("psql failed: " + sql + "\n" + r.getStderr());
        }
    }
}
