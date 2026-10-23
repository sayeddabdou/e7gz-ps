package ps.e7gz.database;

import static org.assertj.core.api.Assertions.assertThat;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.RepoFiles;

/**
 * The migration safety guard (CLAUDE.md section 5, "Migration safety"). Every build proves that the migrations can
 * still be deployed:
 * <ul>
 *   <li>a migration merged to main is never edited, renamed or removed (a deployed database already ran it, and Flyway
 *       refuses to start when one changes or disappears) - checked against {@code migration-lock.txt};</li>
 *   <li>every migration after V1 is named {@code V<yyyyMMddHHmm>__<what>.sql} with a unique version, and is recorded in
 *       the physical model's change report together with its {@code .dbm} change;</li>
 *   <li>every migration applies, one at a time, on a database that already holds data, not only on an empty one, and
 *       loses no rows; Flyway's own validation passes at the end.</li>
 * </ul>
 * Building from an empty database and the database contract scripts are covered by {@link DatabaseContractTest}.
 */
class MigrationSafetyTest {

    private static final Path MIGRATIONS = RepoFiles.get("backend/src/main/resources/db/migration");
    private static final Path CHANGE_REPORT = RepoFiles.get("docs/04-erd/physical/E7GZ-PS-v1.1.27-CHANGE-REPORT.md");
    private static final String LOCK = "ps/e7gz/database/migration-lock.txt";
    private static final String SEED_DIR = "ps/e7gz/database/upgrade-seed/";
    private static final String UPGRADE_DB = "e7gz_upgrade";

    private static final Pattern VERSIONED = Pattern.compile("V(\\d+)__([a-z0-9_]+)\\.sql");
    private static final Pattern MENTIONED = Pattern.compile("V\\d{12}__[a-z0-9_]+\\.sql");

    @Test
    void lockFileListsEveryMigrationUnchanged() throws IOException {
        Map<String, String> locked = lockedChecksums();
        Map<String, String> actual = new LinkedHashMap<>();
        for (String file : versionedFiles()) {
            actual.put(file, sha256(read(MIGRATIONS.resolve(file))));
        }

        for (Map.Entry<String, String> entry : locked.entrySet()) {
            assertThat(actual).as("""
                    %s is in migration-lock.txt but no longer in db/migration. A migration merged to main is never \
                    removed or renamed: a deployed database already ran it. Undo the change with a NEW migration.""",
                    entry.getKey()).containsKey(entry.getKey());
            assertThat(actual.get(entry.getKey())).as("""
                    %s changed after it was locked. A migration merged to main is never edited: a deployed database \
                    already ran it and Flyway would refuse to start. Put the change in a NEW migration.""",
                    entry.getKey()).isEqualTo(entry.getValue());
        }
        for (String file : actual.keySet()) {
            assertThat(locked).as("""
                    %s is not in migration-lock.txt. Add this line at the END of the file in the same PR, after the \
                    Product Owner reviewed the migration:%n%s %s""", file, actual.get(file), file).containsKey(file);
        }
        assertThat(new ArrayList<>(locked.keySet())).as("migration-lock.txt lists migrations in version order")
                .isEqualTo(new ArrayList<>(actual.keySet()));
    }

    @Test
    void laterMigrationsAreTimestampedWithUniqueVersions() throws IOException {
        Set<String> versions = new HashSet<>();
        for (String file : versionedFiles()) {
            Matcher m = VERSIONED.matcher(file);
            assertThat(m.matches()).as("%s: name a migration V<yyyyMMddHHmm>__<what_in_lower_case>.sql", file).isTrue();
            assertThat(m.group(1).equals("1") || m.group(1).length() == 12)
                    .as("%s: a migration after V1 uses a 12-digit timestamp version (CLAUDE.md section 5)", file).isTrue();
            assertThat(versions.add(m.group(1))).as("%s: version %s is used twice", file, m.group(1)).isTrue();
        }
        try (Stream<Path> all = Files.list(MIGRATIONS)) {
            List<String> stray = all.map(p -> p.getFileName().toString())
                    .filter(f -> !VERSIONED.matcher(f).matches() && !f.equals("R__security_baseline.sql")
                            && !f.equals("V1__baseline.sql.conf"))
                    .toList();
            assertThat(stray).as("unexpected files in db/migration (only V<version>__<what>.sql migrations belong here)")
                    .isEmpty();
        }
    }

    @Test
    void everyLaterMigrationIsInTheChangeReportWithItsDbmChange() throws IOException {
        List<String> report = read(CHANGE_REPORT).lines().toList();
        for (String file : versionedFiles()) {
            if (file.startsWith("V1__")) {
                continue;
            }
            int line = indexOfLineContaining(report, file);
            assertThat(line).as("""
                    %s is not named in docs/04-erd/physical/E7GZ-PS-v1.1.27-CHANGE-REPORT.md. Every schema change is a \
                    new migration + the .dbm updated + a change-report entry (CLAUDE.md section 5).""", file)
                    .isNotNegative();
            assertThat(section(report, line)).as("the change-report entry of %s names its .dbm change", file)
                    .contains(".dbm");
        }
    }

    @Test
    void changeReportNamesOnlyMigrationsThatExist() throws IOException {
        Set<String> files = new HashSet<>(versionedFiles());
        Matcher m = MENTIONED.matcher(read(CHANGE_REPORT));
        while (m.find()) {
            assertThat(files).as("the change report names %s, which is not in db/migration (removed or renamed?)",
                    m.group()).contains(m.group());
        }
    }

    /**
     * Applies V1, loads data shaped like real rows, then applies every later migration one at a time. After each step
     * no table has lost a row; a seed file named after a version ({@code upgrade-seed/V<version>.sql}) is loaded right
     * after that version, so rows using its new columns go through every later migration too.
     */
    @Test
    void everyMigrationAppliesOnADatabaseThatAlreadyHoldsData() throws Exception {
        PostgreSQLContainer<?> db = PostgresTestContainer.get();
        psql(db, "DROP DATABASE IF EXISTS " + UPGRADE_DB);
        psql(db, "CREATE DATABASE " + UPGRADE_DB + " TEMPLATE template0");

        List<MigrationVersion> versions = Arrays.stream(flyway(null).info().all())
                .map(MigrationInfo::getVersion)
                .filter(v -> v != null)
                .sorted()
                .toList();
        assertThat(versions).as("versioned migrations").isNotEmpty();

        List<String> seeded = new ArrayList<>();
        for (MigrationVersion version : versions) {
            Map<String, Long> before = rowCounts();
            flyway(version).migrate();
            Map<String, Long> after = rowCounts();
            for (Map.Entry<String, Long> table : before.entrySet()) {
                assertThat(after).as("V%s removed table %s", version, table.getKey()).containsKey(table.getKey());
                assertThat(after.get(table.getKey())).as("V%s lost rows of %s", version, table.getKey())
                        .isGreaterThanOrEqualTo(table.getValue());
            }
            String seed = resource(SEED_DIR + "V" + version + ".sql");
            if (seed != null) {
                execute(seed);
                seeded.add(version.toString());
            }
        }

        assertThat(seeded).as("the V1 seed was loaded").contains("1");
        // The security baseline is repeatable and re-applied after every migration on purpose (its header changes on
        // every run), so Flyway always reports it as pending or outdated; every versioned migration validates strictly.
        Flyway all = flyway(null);
        assertThat(all.validateWithResult().invalidMigrations.stream()
                .filter(invalid -> invalid.version != null && !invalid.version.isEmpty())
                .map(invalid -> invalid.filepath + ": " + invalid.errorDetails.errorMessage)
                .toList()).as("versioned migrations that fail Flyway validation after the upgrade").isEmpty();
        assertThat(Arrays.stream(all.info().pending()).filter(m -> m.getVersion() != null).toList())
                .as("versioned migrations still pending after the upgrade").isEmpty();
        assertThat(rowCounts().get("venue.venue_image")).as("seeded photos survived every migration").isEqualTo(5L);
        // v1.1.33 (E7-40): every job written before the column existed took its own id, whatever its state.
        assertThat(count("""
                SELECT count(*) FROM technical.scheduled_job
                 WHERE correlation_id = id
                   AND id BETWEEN '5afe0000-0000-4000-8000-000000000101' AND '5afe0000-0000-4000-8000-000000000105'"""))
                .as("jobs backfilled with their own id").isEqualTo(5L);
        assertThat(count("""
                SELECT count(*) FROM technical.scheduled_job
                 WHERE id = '5afe0000-0000-4000-8000-000000000109' AND status = 'PENDING' AND attempt = 0"""))
                .as("a frequent job's one row moved to its next run").isEqualTo(1L);
        assertThat(count("SELECT count(*) FROM technical.scheduled_job"
                + " WHERE correlation_id = '5afe0000-0000-4000-8000-000000000107'"))
                .as("jobs that kept their creating command's correlation ID").isEqualTo(2L);
        // v1.1.37 (E7-113): sessions written before superseded_at stayed current; the seeded day keeps its superseded
        // history beside its current session, each with its own booking, and the walk-in in the old window has none.
        assertThat(count("""
                SELECT count(*) FROM venue.operating_session
                 WHERE id IN ('5afe0000-0000-4000-8000-000000000203', '5afe0000-0000-4000-8000-000000000205')
                   AND superseded_at IS NULL"""))
                .as("current sessions").isEqualTo(2L);
        assertThat(count("""
                SELECT count(*) FROM booking.booking b JOIN venue.operating_session s ON s.id = b.operating_session_id
                 WHERE b.id = '5afe0000-0000-4000-8000-000000000204' AND s.superseded_at IS NOT NULL"""))
                .as("the booking of the superseded session still references it").isEqualTo(1L);
        assertThat(count("""
                SELECT count(*) FROM booking.booking
                 WHERE id = '5afe0000-0000-4000-8000-000000000207' AND operating_session_id IS NULL"""))
                .as("the walk-in in the old window only is out of hours").isEqualTo(1L);
    }

    private static long count(String sql) throws SQLException {
        try (Connection c = owner(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** Same Flyway settings as the application (application.yml, spring.flyway.*). */
    private static Flyway flyway(MigrationVersion target) {
        var config = Flyway.configure()
                .dataSource(PostgresTestContainer.jdbcUrl(UPGRADE_DB), MIGRATOR, MIGRATOR)
                .initSql("SET ROLE postgres")
                .defaultSchema("flyway")
                .createSchemas(true)
                .locations("classpath:db/migration");
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    private static Map<String, Long> rowCounts() throws SQLException {
        Map<String, Long> counts = new LinkedHashMap<>();
        try (Connection c = owner(); Statement s = c.createStatement()) {
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = s.executeQuery("""
                    SELECT format('%I.%I', n.nspname, c.relname)
                      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                     WHERE c.relkind IN ('r', 'p')
                       AND n.nspname NOT IN ('pg_catalog', 'information_schema', 'flyway', 'public', 'topology')
                       AND n.nspname NOT LIKE 'pg\\_%'
                     ORDER BY 1""")) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
            for (String table : tables) {
                try (ResultSet rs = s.executeQuery("SELECT count(*) FROM " + table)) {
                    rs.next();
                    counts.put(table, rs.getLong(1));
                }
            }
        }
        return counts;
    }

    private static void execute(String sql) throws SQLException {
        try (Connection c = owner(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static Connection owner() throws SQLException {
        Connection c = DriverManager.getConnection(PostgresTestContainer.jdbcUrl(UPGRADE_DB), MIGRATOR, MIGRATOR);
        try (Statement s = c.createStatement()) {
            s.execute("SET ROLE postgres");
        }
        return c;
    }

    private static void psql(PostgreSQLContainer<?> db, String sql) throws IOException, InterruptedException {
        ExecResult r = db.execInContainer("psql", "-U", "postgres", "-d", "postgres", "-v", "ON_ERROR_STOP=1", "-c", sql);
        if (r.getExitCode() != 0) {
            throw new IllegalStateException("psql failed: " + sql + "\n" + r.getStderr());
        }
    }

    /** Versioned migration files in version order. */
    private static List<String> versionedFiles() throws IOException {
        try (Stream<Path> all = Files.list(MIGRATIONS)) {
            return all.map(p -> p.getFileName().toString())
                    .filter(f -> f.startsWith("V") && f.endsWith(".sql"))
                    .sorted((a, b) -> MigrationVersion.fromVersion(version(a))
                            .compareTo(MigrationVersion.fromVersion(version(b))))
                    .toList();
        }
    }

    private static String version(String file) {
        return file.substring(1, file.indexOf("__"));
    }

    private static Map<String, String> lockedChecksums() throws IOException {
        String lock = resource(LOCK);
        assertThat(lock).as(LOCK).isNotNull();
        Map<String, String> locked = new LinkedHashMap<>();
        for (String line : lock.lines().toList()) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.trim().split("\\s+");
            assertThat(parts).as("migration-lock.txt line: %s", line).hasSize(2);
            assertThat(locked.put(parts[1], parts[0])).as("%s is listed twice in migration-lock.txt", parts[1]).isNull();
        }
        return locked;
    }

    /** The change-report entry around a line: from the nearest heading or version bullet above it to the next one. */
    private static String section(List<String> lines, int at) {
        int start = at;
        while (start > 0 && !isEntryStart(lines.get(start))) {
            start--;
        }
        int end = at + 1;
        while (end < lines.size() && !isEntryStart(lines.get(end))) {
            end++;
        }
        return String.join("\n", lines.subList(start, end));
    }

    private static boolean isEntryStart(String line) {
        return line.startsWith("#") || line.startsWith("- **v");
    }

    private static int indexOfLineContaining(List<String> lines, String text) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(text)) {
                return i;
            }
        }
        return -1;
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = MigrationSafetyTest.class.getClassLoader().getResourceAsStream(name)) {
            return in == null ? null : normalise(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static String read(Path file) throws IOException {
        return normalise(Files.readString(file, StandardCharsets.UTF_8));
    }

    private static String normalise(String s) {
        return s.replace("\r\n", "\n");
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
