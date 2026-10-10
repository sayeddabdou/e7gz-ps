package ps.e7gz.notification.web;

import static org.assertj.core.api.Assertions.assertThat;
import static ps.e7gz.support.fixtures.Fixtures.aUser;
import static ps.e7gz.support.fixtures.Fixtures.aVenueAccount;
import static ps.e7gz.support.fixtures.Fixtures.anAdmin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import ps.e7gz.E7gzApplication;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.codes.VenueAccountStatus;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.NewOutboxEvent;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.messaging.internal.OutboxDrain;
import ps.e7gz.notification.internal.stream.VenueStreamHub;
import ps.e7gz.notification.internal.stream.VenueStreamSignals;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.fixtures.Fixtures;
import ps.e7gz.venue.internal.VenueAccounts;

/**
 * E7-44: the venue live channel over real HTTP between two API instances on one database (ADR-014, arch 08 section 5).
 * Instance A is this test's context (heartbeat every 500 ms); instance B is a second application started here with a
 * 1-hour heartbeat, so only the {@code NOTIFY} path can reach its streams. The worker's consumer runs through the real
 * outbox dispatcher ({@link OutboxDrain}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "e7gz.venue-stream.heartbeat=500ms")
class VenueLiveChannelTest extends IntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static ConfigurableApplicationContext instanceB;

    @LocalServerPort
    int portA;

    @Autowired
    AccessTokens accessTokens;

    @Autowired
    CommandRunner runner;

    @Autowired
    Outbox outbox;

    @Autowired
    VenueAccounts venueAccounts;

    @Autowired
    VenueStreamHub hubA;

    @Autowired
    DataSource appDataSource;

    @Autowired
    ObjectMapper json;

    final HttpClient http = HttpClient.newHttpClient();
    CorrelationId.Scope correlation;

    @BeforeAll
    static void startInstanceB() {
        Map<String, Object> properties = new HashMap<>(IntegrationTest.properties());
        properties.put("server.port", "0");
        properties.put("e7gz.venue-stream.heartbeat", "1h");
        // As arguments: SpringApplicationBuilder.properties are defaults, which application.yml would override.
        instanceB = new SpringApplicationBuilder(E7gzApplication.class).run(properties.entrySet().stream()
                .map(property -> "--" + property.getKey() + "=" + property.getValue()).toArray(String[]::new));
    }

    @AfterAll
    static void stopInstanceB() {
        if (instanceB != null) {
            instanceB.close();
        }
    }

    @BeforeEach
    void setUp() {
        correlation = CorrelationId.open(CorrelationId.generate());
    }

    @AfterEach
    void tearDown() {
        correlation.close();
    }

    /** The task's "done when": an event committed on one instance reaches a stream on the other. */
    @Test
    void anEventCommittedOnOneInstanceReachesAStreamOnTheOther() throws Exception {
        Fixtures.VenueAccount venue = aVenueAccount().create();
        SseLines onB = open(portB(), venue.id(), hubB());
        UUID booking = UUID.randomUUID();

        bookingCreated(venue.venueId(), booking);                            // committed through instance A
        deliver();                                                           // the worker

        JsonNode signal = nextSignal(onB);
        assertThat(signal.properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("type", "id", "reason");          // a signal, never business data
        assertThat(signal.path("type").asText()).isEqualTo("BOOKING");
        assertThat(signal.path("id").asText()).isEqualTo(booking.toString());
        assertThat(signal.path("reason").asText()).isEqualTo("CREATED");
        onB.close();
    }

    @Test
    void aVenueNeverHearsAnotherVenuesSignals() throws Exception {
        Fixtures.VenueAccount mine = aVenueAccount().create();
        Fixtures.VenueAccount theirs = aVenueAccount().create();
        SseLines onA = open(portA, mine.id(), hubA);
        SseLines theirsOnB = open(portB(), theirs.id(), hubB());
        UUID theirBooking = UUID.randomUUID();
        UUID myBooking = UUID.randomUUID();

        bookingCreated(theirs.venueId(), theirBooking);
        bookingCreated(mine.venueId(), myBooking);
        deliver();

        assertThat(nextSignal(onA).path("id").asText()).isEqualTo(myBooking.toString());
        assertThat(nextSignal(theirsOnB).path("id").asText()).isEqualTo(theirBooking.toString());
        onA.close();
        theirsOnB.close();
    }

    /** FR-VEN-08, PO decision 10 Oct 2026: closed at once by VenueAccountDisabled, not by B's (1-hour) heartbeat. */
    @Test
    void disablingTheAccountClosesItsOpenStreamsAtOnce() throws Exception {
        Fixtures.VenueAccount venue = aVenueAccount().create();
        SseLines onB = open(portB(), venue.id(), hubB());

        venueAccounts.setStatus(anAdmin().create().id(), venue.id(), VenueAccountStatus.DISABLED, "Support ticket 7");
        deliver();

        assertThat(onB.awaitClosed()).isTrue();
        await(() -> hubB().openStreams(venue.id()) == 0);
        assertThat(JdbcClient.create(appDataSource).sql("""
                        SELECT count(*) FROM technical.outbox_event
                         WHERE event_type = 'VenueAccountDisabled' AND aggregate_id = :account""")
                .param("account", venue.id()).query(Integer.class).single()).isEqualTo(1);
        // A disabled account cannot open a new one (the per-request active check, arch 10 section 2.3).
        assertThat(status(portB(), accessTokens.issue(AuthSubjectType.VENUE, venue.id()))).isEqualTo(401);
    }

    /** The backup (PO decision 10 Oct 2026): A's heartbeat re-reads the status and closes the stream. */
    @Test
    void theHeartbeatKeepsTheStreamAliveAndClosesItWhenTheAccountIsNoLongerEnabled() throws Exception {
        Fixtures.VenueAccount venue = aVenueAccount().create();
        SseLines onA = open(portA, venue.id(), hubA);
        assertThat(onA.awaitLine(": heartbeat")).isTrue();

        JdbcClient.create(appDataSource).sql("UPDATE iam.venue_account SET account_status = 'DISABLED' WHERE id = :id")
                .param("id", venue.id()).update();                          // no event: only the heartbeat sees it

        assertThat(onA.awaitClosed()).isTrue();
    }

    @Test
    void onlyAnEnabledVenueAccountOpensAStream() throws Exception {
        assertThat(status(portA, null)).isEqualTo(401);
        assertThat(status(portA, accessTokens.issue(AuthSubjectType.CUSTOMER, aUser().verified().create().id())))
                .isEqualTo(403);
        Fixtures.VenueAccount disabled = aVenueAccount().disabled().create();
        assertThat(status(portA, accessTokens.issue(AuthSubjectType.VENUE, disabled.id()))).isEqualTo(401);
    }

    // ------------------------------------------------------------------------------------------------------- helpers

    private void bookingCreated(UUID venueId, UUID bookingId) {
        runner.run(new Command<Void>() {
            @Override
            public EligibilityCategory eligibilityCategory() {
                return EligibilityCategory.A_NEW_ACTIVITY_MARKETPLACE;
            }

            @Override
            public CommandScope scope() {
                return CommandScope.shared(LockSet.empty());
            }

            @Override
            public Void execute(CommandContext context) {
                outbox.publish(context, new NewOutboxEvent(IntegrationEvents.BOOKING_CREATED, "BOOKING", bookingId,
                        Map.of("bookingId", bookingId, "venueId", venueId)));
                return null;
            }
        });
    }

    private void deliver() {
        OutboxDrain.drain(appDataSource, json, new VenueStreamSignals(appDataSource, json));
    }

    private int portB() {
        return Integer.parseInt(instanceB.getEnvironment().getProperty("local.server.port"));
    }

    private static VenueStreamHub hubB() {
        return instanceB.getBean(VenueStreamHub.class);
    }

    private JsonNode nextSignal(SseLines stream) throws Exception {
        String data = stream.nextData();
        assertThat(data).as("a change signal").isNotNull();
        return json.readTree(data);
    }

    private int status(int port, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + "/api/v1/venue/stream")).timeout(WAIT);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    /** Opens the stream as the Dashboard does (fetch with the Bearer header) and waits until the hub listens. */
    private SseLines open(int port, UUID venueAccountId, VenueStreamHub hub) throws Exception {
        String token = accessTokens.issue(AuthSubjectType.VENUE, venueAccountId);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/venue/stream"))
                .header("Authorization", "Bearer " + token).header("Accept", "text/event-stream").build();
        HttpResponse<java.util.stream.Stream<String>> response = http.send(request, HttpResponse.BodyHandlers.ofLines());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        SseLines stream = new SseLines(response.body());
        await(() -> hub.listensFor(venueAccountId));
        return stream;
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("waited " + WAIT).isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    /** The lines of one open stream, read on a thread of their own. */
    static final class SseLines implements AutoCloseable {
        private static final String CLOSED = "\u0000closed";
        private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        private final java.util.stream.Stream<String> body;

        SseLines(java.util.stream.Stream<String> body) {
            this.body = body;
            Thread.ofVirtual().start(() -> {
                try {
                    Iterator<String> it = body.iterator();
                    while (it.hasNext()) {
                        lines.add(it.next());
                    }
                } catch (RuntimeException closed) {
                    // the test closed it
                } finally {
                    lines.add(CLOSED);
                }
            });
        }

        /** The {@code data} of the next {@code change} event, or null when none comes in time. */
        String nextData() throws InterruptedException {
            boolean change = false;
            String line;
            while ((line = lines.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS)) != null && !CLOSED.equals(line)) {
                if (line.equals("event:change")) {
                    change = true;
                } else if (change && line.startsWith("data:")) {
                    return line.substring("data:".length());
                }
            }
            return null;
        }

        boolean awaitLine(String expected) throws InterruptedException {
            String line;
            while ((line = lines.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS)) != null && !CLOSED.equals(line)) {
                if (line.equals(expected)) {
                    return true;
                }
            }
            return false;
        }

        boolean awaitClosed() throws InterruptedException {
            String line;
            while ((line = lines.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS)) != null) {
                if (CLOSED.equals(line)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void close() {
            body.close();
        }
    }
}
