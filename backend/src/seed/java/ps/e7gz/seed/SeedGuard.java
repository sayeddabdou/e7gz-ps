package ps.e7gz.seed;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Where the seed may write (PO decision 5 Oct 2026, E7-28): only a PostgreSQL on this machine - the seeded logins are
 * public - and only a fresh one, so seeded rows never mix with real or hand-made ones. Checked before the application
 * context starts (Flyway would otherwise migrate the target) and again on the database itself.
 */
final class SeedGuard {

    private static final Pattern HOST = Pattern.compile("^jdbc:postgresql://(\\[[^\\]]+]|[^/:?,\\[]+)(:\\d+)?/.*");
    private static final Set<String> LOCAL = Set.of("localhost", "127.0.0.1", "[::1]");

    private SeedGuard() {
    }

    /** Refuses any JDBC URL that is not a single PostgreSQL host on this machine. */
    static void requireLocal(String jdbcUrl) {
        Matcher m = HOST.matcher(jdbcUrl == null ? "" : jdbcUrl);
        if (!m.matches() || !LOCAL.contains(m.group(1).toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("The seed writes public dev logins, so it only runs against a database on "
                    + "this machine (localhost / 127.0.0.1 / [::1]); refused: " + jdbcUrl);
        }
    }

    /** Refuses a database that already holds areas, venues or Customers. An existing admin is refused later, by the bootstrap. */
    static void requireFresh(JdbcClient db) {
        boolean used = db.sql("""
                        SELECT EXISTS (SELECT 1 FROM geo.area) OR EXISTS (SELECT 1 FROM venue.venue)
                            OR EXISTS (SELECT 1 FROM iam.user_account)""")
                .query(Boolean.class).single();
        if (used) {
            throw new IllegalStateException("The seed needs a fresh database, and this one already has areas, venues "
                    + "or customers. Start from an empty one: `docker compose down -v && docker compose up -d`, or "
                    + "point E7GZ_DB_URL at an empty database.");
        }
    }
}
