package ps.e7gz.support.fixtures;

import static ps.e7gz.support.PostgresTestContainer.ADMIN_RUNTIME;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.postgresql.Driver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import ps.e7gz.support.PostgresTestContainer;

/**
 * The connections the fixture builders write on, and the unique values they make up. {@code create()} writes as the
 * migration owner and commits at once; {@code create(db)} writes on the caller's connection instead, e.g. a command's
 * {@code ctx.jdbc()}, so the rows roll back with it.
 */
final class FixtureDb {

    /** Same start in one JVM, a different one in the next: values stay unique in a database reused between runs. */
    private static final AtomicLong SEQUENCE = new AtomicLong(ThreadLocalRandom.current().nextLong(1L << 40));

    private static JdbcClient owner;
    private static JdbcClient adminRuntime;

    private FixtureDb() {
    }

    /** The migration owner: no grants in the way, as the existing SQL fixtures write. */
    static synchronized JdbcClient owner() {
        if (owner == null) {
            owner = client(MIGRATOR);
        }
        return owner;
    }

    /** {@code iam.admin_account} is written only by the admin runtime role (E7-23, Sprint 0 review S-6). */
    static synchronized JdbcClient adminRuntime() {
        if (adminRuntime == null) {
            adminRuntime = client(ADMIN_RUNTIME);
        }
        return adminRuntime;
    }

    private static JdbcClient client(String role) {
        return JdbcClient.create(new SimpleDriverDataSource(new Driver(), PostgresTestContainer.jdbcUrl(DATABASE),
                role, role));
    }

    static long next() {
        return SEQUENCE.incrementAndGet();
    }

    /** A short unique suffix for names, logins and usernames. */
    static String unique() {
        return Long.toString(next(), 36);
    }

    /** A unique Egyptian mobile number, local form {@code 01XXXXXXXXX}. */
    static String uniqueLocalMobile() {
        return "010" + String.format("%08d", next() % 100_000_000L);
    }

    static UUID id() {
        return UUID.randomUUID();
    }
}
