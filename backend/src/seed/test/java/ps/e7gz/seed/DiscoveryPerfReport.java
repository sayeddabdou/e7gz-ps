package ps.e7gz.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static ps.e7gz.support.PostgresTestContainer.ADMIN_RUNTIME;
import static ps.e7gz.support.PostgresTestContainer.APP_RUNTIME;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.testcontainers.containers.Container.ExecResult;
import ps.e7gz.E7gzApplication;
import ps.e7gz.inventory.availability.api.NearestAvailabilityProjection;
import ps.e7gz.support.ObjectStoreTestContainer;
import ps.e7gz.support.PostgresTestContainer;

/**
 * E7-34 done-when: "p95 &lt; 1 s on the perf seed (S1-P6) - test recorded in CI as a non-blocking report" (NFR-11, arch
 * 11 section 2: the venue list with Nearest Availability, one area).
 *
 * <p>Not part of {@code check}: {@code ./gradlew discoveryPerf} runs it alone (CI: a {@code continue-on-error} step). It
 * fills a fresh database with the perf seed (launch volume x10: 150 venues), rebuilds the Nearest Availability
 * projection as the {@code availability-rebuild} profile would, starts the application on a random port and times
 * {@code GET /api/v1/customer/venues} over real HTTP: area only, location only, area and location, and next pages
 * through the cursor. It prints and writes the p50 / p95 / p99 and fails, without blocking the build, when p95 is 1 s
 * or more.
 */
@Tag("discovery-perf")
class DiscoveryPerfReport {

    static final Duration TARGET_P95 = Duration.ofSeconds(1);
    private static final int WARM_UP = 60;
    private static final int SAMPLES = 600;
    private static final String[] ACTIVITIES = {"PLAYSTATION", "PLAYSTATION", "BILLIARDS", "PING_PONG"};

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Test
    void theVenueListMeetsItsP95OnThePerfSeed() throws Exception {
        String url = freshDatabase("discovery_perf");
        long seedStart = System.nanoTime();
        SeedApplication.Report seeded = SeedApplication.run(SeedApplication.Mode.PERF, PerfSeed.Volume.PERF,
                connectTo(url));
        Duration seeding = Duration.ofNanos(System.nanoTime() - seedStart);

        try (ConfigurableApplicationContext app = start(url)) {
            long rebuildStart = System.nanoTime();
            NearestAvailabilityProjection.RebuildReport rebuilt =
                    app.getBean(NearestAvailabilityProjection.class).rebuild();
            Duration rebuilding = Duration.ofNanos(System.nanoTime() - rebuildStart);
            String base = "http://localhost:" + app.getEnvironment().getProperty("local.server.port");

            List<String> areas = new ArrayList<>();
            get(base + "/api/v1/customer/areas").path("data").forEach(area -> areas.add(area.path("id").asText()));
            assertThat(areas).isNotEmpty();

            Random random = new Random(34);
            for (int i = 0; i < WARM_UP; i++) {
                timed(base, scenario(random, areas), null);
            }
            Map<String, List<Long>> byScenario = new LinkedHashMap<>();
            List<Long> all = new ArrayList<>();
            long nonEmpty = 0;
            while (all.size() < SAMPLES) {
                Scenario scenario = scenario(random, areas);
                Sample first = timed(base, scenario, null);
                record(byScenario, all, scenario.name(), first.nanos());
                nonEmpty += first.items() > 0 ? 1 : 0;
                if (first.nextCursor() != null && all.size() < SAMPLES) {
                    record(byScenario, all, scenario.name() + " (next page)",
                            timed(base, scenario, first.nextCursor()).nanos());
                }
            }

            String report = report(seeded, seeding, rebuilt, rebuilding, byScenario, all, nonEmpty);
            System.out.println(report);
            Path out = Path.of(System.getProperty("e7gz.discoveryPerf.report", "build/reports/discovery-perf.md"));
            Files.createDirectories(out.toAbsolutePath().getParent());
            Files.writeString(out, report, StandardCharsets.UTF_8);

            assertThat(nonEmpty).as("the workload lists venues").isPositive();
            assertThat(Duration.ofNanos(percentile(all, 95))).as("p95 of GET /customer/venues")
                    .isLessThan(TARGET_P95);
        }
    }

    // ------------------------------------------------------------------------------------------ workload

    /** One list request shape; {@code query} has no cursor. */
    private record Scenario(String name, String query) {
    }

    private record Sample(long nanos, int items, String nextCursor) {
    }

    /** Cairo, around the perf seed's six districts. */
    private static Scenario scenario(Random random, List<String> areas) {
        String activity = "activity=" + ACTIVITIES[random.nextInt(ACTIVITIES.length)];
        String area = "areaId=" + areas.get(random.nextInt(areas.size()));
        String location = String.format(Locale.ROOT, "lat=%.5f&lng=%.5f", 29.95 + random.nextDouble() * 0.17,
                31.18 + random.nextDouble() * 0.33);
        return switch (random.nextInt(3)) {
            case 0 -> new Scenario("area only", area + "&" + activity);
            case 1 -> new Scenario("location only", location + "&" + activity);
            default -> new Scenario("area + location", area + "&" + location + "&" + activity);
        };
    }

    private Sample timed(String base, Scenario scenario, String cursor) throws Exception {
        String uri = base + "/api/v1/customer/venues?" + scenario.query()
                + (cursor == null ? "" : "&cursor=" + cursor);
        long start = System.nanoTime();
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(uri)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        long nanos = System.nanoTime() - start;
        assertThat(response.statusCode()).as(uri + " " + response.body()).isEqualTo(200);
        JsonNode data = json.readTree(response.body()).path("data");
        return new Sample(nanos, data.path("items").size(),
                data.path("nextCursor").isNull() ? null : data.path("nextCursor").asText());
    }

    private static void record(Map<String, List<Long>> byScenario, List<Long> all, String name, long nanos) {
        byScenario.computeIfAbsent(name, n -> new ArrayList<>()).add(nanos);
        all.add(nanos);
    }

    private JsonNode get(String uri) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(uri)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(uri).isEqualTo(200);
        return json.readTree(response.body());
    }

    // ------------------------------------------------------------------------------------------ report

    /** Nearest-rank percentile, in nanoseconds. */
    static long percentile(List<Long> samples, int p) {
        List<Long> sorted = samples.stream().sorted().toList();
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.max(rank, 1) - 1);
    }

    private static String report(SeedApplication.Report seeded, Duration seeding,
                                 NearestAvailabilityProjection.RebuildReport rebuilt, Duration rebuilding,
                                 Map<String, List<Long>> byScenario, List<Long> all, long nonEmpty) {
        long p95 = percentile(all, 95);
        StringBuilder out = new StringBuilder();
        out.append("## E7-34 discovery p95 (NFR-11)\n\n");
        out.append(String.format(Locale.ROOT, "**GET /api/v1/customer/venues p95 = %s ms** (target < %d ms): %s%n%n",
                ms(p95), TARGET_P95.toMillis(), p95 < TARGET_P95.toNanos() ? "PASS" : "FAIL (non-blocking)"));
        out.append(String.format(Locale.ROOT, "Perf seed: %d venues (%d ACTIVE), %d resources, %d operating sessions,"
                        + " seeded in %ds. Projection rebuild: %d pairs, %d rows, in %ds.%n%n",
                seeded.counts().get("venues"), seeded.counts().get("venues ACTIVE"), seeded.counts().get("resources"),
                seeded.counts().get("operating sessions"), seeding.toSeconds(), rebuilt.pairs(), rebuilt.written(),
                rebuilding.toSeconds()));
        out.append(String.format(Locale.ROOT, "%d timed requests after %d warm-up, page size 20, one client; %d first"
                + " pages listed venues.%n%n", all.size(), WARM_UP, nonEmpty));
        out.append("| requests | n | p50 ms | p95 ms | p99 ms | max ms |\n|---|---:|---:|---:|---:|---:|\n");
        out.append(row("all", all));
        byScenario.forEach((name, samples) -> out.append(row(name, samples)));
        return out.toString();
    }

    private static String row(String name, List<Long> samples) {
        return String.format(Locale.ROOT, "| %s | %d | %s | %s | %s | %s |%n", name, samples.size(),
                ms(percentile(samples, 50)), ms(percentile(samples, 95)), ms(percentile(samples, 99)),
                ms(percentile(samples, 100)));
    }

    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.1f", nanos / 1_000_000.0);
    }

    // ------------------------------------------------------------------------------------------ environment

    /** The application as the {@code api} profile runs it, on a random port, against the seeded database. */
    private static ConfigurableApplicationContext start(String url) {
        Map<String, Object> properties = new HashMap<>(connectTo(url));
        properties.put("server.port", "0");
        properties.put("e7gz.security.jwt.secret", ps.e7gz.support.IntegrationTest.TEST_JWT_SECRET);
        properties.put("e7gz.identity.otp-payload-key", ps.e7gz.support.IntegrationTest.TEST_OTP_PAYLOAD_KEY);
        // Card photos are signed links; signing needs the store's address, not the store.
        properties.put("e7gz.object-store.endpoint", ObjectStoreTestContainer.endpoint().toString());
        properties.put("e7gz.object-store.bucket", ObjectStoreTestContainer.BUCKET);
        properties.put("e7gz.object-store.access-key", ObjectStoreTestContainer.ACCESS_KEY);
        properties.put("e7gz.object-store.secret-key", ObjectStoreTestContainer.SECRET_KEY);
        properties.put("e7gz.object-store.create-bucket", "false");
        SpringApplication application = new SpringApplication(E7gzApplication.class);
        application.setAdditionalProfiles("api");
        application.addListeners((ApplicationEnvironmentPreparedEvent event) -> event.getEnvironment()
                .getPropertySources().addFirst(new MapPropertySource("e7gz-discovery-perf", properties)));
        return application.run();
    }

    private static String freshDatabase(String name) throws Exception {
        psql("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
        psql("CREATE DATABASE " + name + " TEMPLATE template0 ENCODING 'UTF8'");
        return PostgresTestContainer.jdbcUrl(name);
    }

    private static Map<String, Object> connectTo(String url) {
        return Map.of(
                "spring.flyway.url", url,
                "spring.flyway.user", MIGRATOR,
                "spring.flyway.password", MIGRATOR,
                "e7gz.datasource.app.jdbc-url", url,
                "e7gz.datasource.app.username", APP_RUNTIME,
                "e7gz.datasource.app.password", APP_RUNTIME,
                "e7gz.datasource.admin.jdbc-url", url,
                "e7gz.datasource.admin.username", ADMIN_RUNTIME,
                "e7gz.datasource.admin.password", ADMIN_RUNTIME);
    }

    private static void psql(String sql) throws Exception {
        ExecResult r = PostgresTestContainer.get().execInContainer("psql", "-U", "postgres", "-d", "postgres", "-v",
                "ON_ERROR_STOP=1", "-c", sql);
        if (r.getExitCode() != 0) {
            throw new IllegalStateException("psql failed: " + sql + "\n" + r.getStderr());
        }
    }
}
