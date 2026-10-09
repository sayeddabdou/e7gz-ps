package ps.e7gz.seed;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.simple.JdbcClient;
import ps.e7gz.E7gzApplication;

/**
 * One command fills a fresh local database with test data (E7-28):
 * <pre>
 * docker compose up -d
 * cd backend && ./gradlew seedDev      # a small Cairo marketplace to click through (see DevSeed)
 * cd backend && ./gradlew seedPerf     # launch-city volume x10 for load tests (see PerfSeed)
 * </pre>
 * It boots the application context without a web server or workers - Flyway migrates the database first, as on any
 * start - writes through the modules' services and ends. The database is the one the application uses
 * ({@code E7GZ_DB_URL}, default the docker compose one); {@link SeedGuard} refuses one that is not on this machine or
 * not fresh. Logins: {@link SeedCredentials}.
 */
public final class SeedApplication {

    private static final Logger log = LoggerFactory.getLogger(SeedApplication.class);

    /** The properties holding the three database URLs the context would connect to. */
    private static final List<String> DATABASE_URLS =
            List.of("spring.flyway.url", "e7gz.datasource.app.jdbc-url", "e7gz.datasource.admin.jdbc-url");

    public enum Mode { DEV, PERF }

    /** What the database holds after the seed. */
    public record Report(Map<String, Long> counts) {
    }

    private SeedApplication() {
    }

    public static void main(String[] args) {
        Mode mode = args.length == 1 ? Mode.valueOf(args[0].toUpperCase(Locale.ROOT)) : null;
        if (mode == null) {
            throw new IllegalArgumentException("Usage: SeedApplication dev|perf");
        }
        Report report = run(mode, PerfSeed.Volume.PERF, Map.of());
        report.counts().forEach((what, count) -> log.info("  {}: {}", what, count));
        if (mode == Mode.DEV) {
            log.info("Logins (local only): admin {} / {}; venues levelup, pixelarena, cueconsole, gamezone, controller,"
                            + " spinclub / {}, Owner PIN {}; customers 01000000001…01000000020 / {}",
                    SeedCredentials.ADMIN_EMAIL, SeedCredentials.PASSWORD, SeedCredentials.PASSWORD,
                    SeedCredentials.OWNER_PIN, SeedCredentials.PASSWORD);
        }
        System.exit(0);
    }

    /**
     * Seeds and returns the counts. {@code volume} is used by {@link Mode#PERF} only; {@code properties} take
     * precedence over every other source (the seed test points the context at its own database with them).
     */
    public static Report run(Mode mode, PerfSeed.Volume volume, Map<String, Object> properties) {
        Map<String, Object> seed = new HashMap<>();
        seed.put("spring.main.web-application-type", WebApplicationType.NONE.name());
        // The seed issues no token, but the context needs a signing key: a random one, used for nothing.
        seed.put("e7gz.security.jwt.secret", randomKey(64));
        // Likewise the OTP payload key (E7-41): the seed sends no OTP.
        seed.put("e7gz.identity.otp-payload-key", randomKey(32));
        // And the idempotency fingerprint key (E7-42): the seed makes no idempotent write.
        seed.put("e7gz.idempotency.fingerprint-key", randomKey(32));
        seed.put("e7gz.object-store.create-bucket", "false");
        // Several venues are onboarded at once; let them queue for the Argon2id gate instead of being refused.
        seed.put("e7gz.password-hashing.acquire-timeout", "10m");
        seed.putAll(properties);

        SpringApplication application = new SpringApplication(E7gzApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.addListeners((ApplicationEnvironmentPreparedEvent event) -> {
            ConfigurableEnvironment environment = event.getEnvironment();
            environment.getPropertySources().addFirst(new MapPropertySource("e7gz-seed", seed));
            // Before any bean exists: Flyway has not touched the database yet.
            DATABASE_URLS.forEach(name -> SeedGuard.requireLocal(environment.getProperty(name)));
        });
        try (ConfigurableApplicationContext context = application.run()) {
            JdbcClient db = JdbcClient.create(context.getBean(DataSource.class));
            SeedGuard.requireFresh(db);
            Seeder seeder = new Seeder(context);
            log.info("Seeding {} data", mode.name().toLowerCase(Locale.ROOT));
            switch (mode) {
                case DEV -> DevSeed.run(seeder);
                case PERF -> PerfSeed.run(seeder, volume);
            }
            return new Report(counts(db));
        }
    }

    private static Map<String, Long> counts(JdbcClient db) {
        Map<String, Long> counts = new LinkedHashMap<>();
        count(db, counts, "areas", "SELECT count(*) FROM geo.area");
        count(db, counts, "venues", "SELECT count(*) FROM venue.venue");
        count(db, counts, "venues ACTIVE", "SELECT count(*) FROM venue.venue WHERE marketplace_status = 'ACTIVE'");
        count(db, counts, "venue accounts", "SELECT count(*) FROM iam.venue_account");
        count(db, counts, "resources", "SELECT count(*) FROM resource.resource");
        count(db, counts, "default prices", "SELECT count(*) FROM pricing.venue_default_price");
        count(db, counts, "price overrides", "SELECT count(*) FROM pricing.resource_price_override");
        count(db, counts, "opening templates", "SELECT count(*) FROM venue.venue_opening_template");
        count(db, counts, "operating sessions", "SELECT count(*) FROM venue.operating_session");
        count(db, counts, "customers", "SELECT count(*) FROM iam.user_account");
        count(db, counts, "customers verified", "SELECT count(*) FROM iam.user_account WHERE phone_verified");
        return counts;
    }

    private static void count(JdbcClient db, Map<String, Long> counts, String name, String sql) {
        counts.put(name, db.sql(sql).query(Long.class).single());
    }

    private static String randomKey(int bytes) {
        byte[] key = new byte[bytes];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}
