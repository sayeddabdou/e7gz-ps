package ps.e7gz.messaging.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.messaging.api.NewOutboxEvent;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.messaging.api.OutboxConsumer;
import ps.e7gz.messaging.api.OutboxEvent;
import ps.e7gz.messaging.api.PermanentDeliveryFailure;
import ps.e7gz.support.IntegrationTest;

/**
 * E7-41 done-when (ADR-004, arch 08 section 4): a rolled-back command leaves nothing to dispatch; a transient failure is
 * retried after the next delay of arch 08 section 4.3; a permanent failure, or the last attempt failing, ends in
 * {@code DEAD_LETTER} with {@code last_error}. Also: the envelope the writer stores, the {@code pg_notify('outbox')}
 * sent only at commit, the correlation ID around each consumer, every consumer running again on a retry, and the
 * order rule (PO decision 9 Oct 2026). Every test uses event types of its own, so no dispatcher here takes another
 * test's events.
 */
class OutboxDispatcherTest extends IntegrationTest {

    private static final OutboxProperties ARCH_08 = new OutboxProperties(Duration.ofSeconds(1), 1,
            List.of(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10),
                    Duration.ofHours(1)));
    /** The same six attempts, each due at once, so a test can use them all up. */
    private static final OutboxProperties IMMEDIATE = new OutboxProperties(Duration.ofSeconds(1), 1,
            Collections.nCopies(5, Duration.ofMillis(1)));

    @Autowired
    CommandRunner runner;

    @Autowired
    Outbox outbox;

    @Autowired
    JdbcOutboxStore store;

    @Autowired
    DataSource appDataSource;

    @Autowired
    ObjectMapper json;

    // ------------------------------------------------------------------------------------------------- the writer

    @Test
    void theWriterStoresAPendingEventWithTheCorrelationIdAndTheCommandInstant() {
        String type = newType();
        UUID aggregate = UUID.randomUUID();
        UUID correlation = CorrelationId.generate();
        Instant[] commandNow = new Instant[1];

        long id;
        try (CorrelationId.Scope ignored = CorrelationId.open(correlation)) {
            id = runner.run(command(context -> {
                commandNow[0] = context.commandNow();
                return outbox.publish(context,
                        new NewOutboxEvent(type, "VENUE", aggregate, Map.of("venueId", aggregate, "n", 7)));
            }));
        }

        Map<String, Object> row = row(id);
        assertThat(row.get("event_type")).isEqualTo(type);
        assertThat(row.get("aggregate_type")).isEqualTo("VENUE");
        assertThat(row.get("aggregate_id")).isEqualTo(aggregate);
        assertThat(row.get("correlation_id")).isEqualTo(correlation);
        assertThat(row.get("state")).isEqualTo("PENDING");
        assertThat(attempt(id)).isZero();
        assertThat(instant(id, "created_at")).isEqualTo(commandNow[0]);
        assertThat(row.get("payload").toString()).contains("\"venueId\": \"" + aggregate + "\"").contains("\"n\": 7");
    }

    @Test
    void theWriterNeedsACorrelationId() {
        String type = newType();
        assertThatThrownBy(() -> runner.run(command(context -> outbox.publish(context,
                new NewOutboxEvent(type, "VENUE", UUID.randomUUID(), Map.of())))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("correlation");
        assertThat(count(type)).isZero();
    }

    // -------------------------------------------------------------------------------------- after commit, never before

    @Test
    void aRolledBackCommandLeavesNoEventAndNothingIsDispatched() {
        String type = newType();
        List<OutboxEvent> delivered = new CopyOnWriteArrayList<>();

        try (CorrelationId.Scope ignored = CorrelationId.open(CorrelationId.generate())) {
            assertThatThrownBy(() -> runner.run(command(context -> {
                publish(context, type);
                throw new IllegalStateException("the business write fails after the outbox insert");
            }))).hasMessageContaining("after the outbox insert");
        }

        assertThat(count(type)).isZero();
        assertThat(drain(ARCH_08, consumer(type, delivered::add))).isZero();
        assertThat(delivered).isEmpty();
    }

    @Test
    void aCommittedEventIsDispatchedOnceWithItsCorrelationIdOpen() {
        String type = newType();
        UUID correlation = CorrelationId.generate();
        List<UUID> seen = new CopyOnWriteArrayList<>();
        long id = publishCommitted(type, correlation);

        assertThat(drain(ARCH_08, consumer(type, event -> seen.add(CorrelationId.require())))).isEqualTo(1);
        assertThat(drain(ARCH_08, consumer(type, event -> seen.add(CorrelationId.require())))).isZero();

        assertThat(seen).containsExactly(correlation);
        Map<String, Object> row = row(id);
        assertThat(row.get("state")).isEqualTo("DISPATCHED");
        assertThat(attempt(id)).isOne();
        assertThat(row.get("dispatched_at")).isNotNull();
        assertThat(CorrelationId.current()).isEmpty();
    }

    @Test
    void pgNotifyReachesTheListenerOnlyWhenTheCommandCommits() throws Exception {
        String type = newType();
        try (Connection listener = appDataSource.getConnection()) {
            try (Statement statement = listener.createStatement()) {
                statement.execute("LISTEN " + JdbcOutboxStore.CHANNEL);
            }
            PGConnection notifications = listener.unwrap(PGConnection.class);
            try {
                try (CorrelationId.Scope ignored = CorrelationId.open(CorrelationId.generate())) {
                    assertThatThrownBy(() -> runner.run(command(context -> {
                        publish(context, type);
                        throw new IllegalStateException("rolled back");
                    }))).hasMessage("rolled back");
                }
                assertThat(received(notifications, 300)).as("after a rollback").isEmpty();

                publishCommitted(type, CorrelationId.generate());
                assertThat(received(notifications, 5_000)).as("after a commit").contains(JdbcOutboxStore.CHANNEL);
            } finally {
                try (Statement statement = listener.createStatement()) {
                    statement.execute("UNLISTEN *");
                }
            }
        }
    }

    // ----------------------------------------------------------------------------------------------- retry, dead letter

    @Test
    void aTransientFailureIsRetriedAfterTheNextDelayOfTheTable() {
        String type = newType();
        AtomicInteger calls = new AtomicInteger();
        OutboxConsumer failsOnce = consumer(type, event -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("provider timeout");
            }
        });
        long id = publishCommitted(type, CorrelationId.generate());

        assertThat(drain(ARCH_08, failsOnce)).isEqualTo(1);
        Map<String, Object> retried = row(id);
        assertThat(retried.get("state")).isEqualTo("PENDING");
        assertThat(attempt(id)).isOne();
        assertThat((String) retried.get("last_error")).contains("IllegalStateException: provider timeout");
        Duration wait = Duration.between(dbNow(), instant(id, "next_attempt_at"));
        assertThat(wait).as("attempt 2 is due 5 s later (arch 08 section 4.3)")
                .isBetween(Duration.ofSeconds(3), Duration.ofSeconds(5));
        assertThat(drain(ARCH_08, failsOnce)).as("not due yet").isZero();

        assertThat(drain(IMMEDIATE, failsOnce)).isZero();        // still not due: the stored instant decides
        makeDue(id);
        assertThat(drain(ARCH_08, failsOnce)).isEqualTo(1);
        assertThat(row(id).get("state")).isEqualTo("DISPATCHED");
        assertThat(attempt(id)).isEqualTo(2);
        assertThat(calls).hasValue(2);
    }

    @Test
    void theDelaysFollowTheTableOfArch08() {
        assertThat(ARCH_08.maxAttempts()).isEqualTo(6);
        assertThat(List.of(1, 2, 3, 4, 5).stream().map(ARCH_08::delayAfter).toList()).containsExactly(
                Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10),
                Duration.ofHours(1));
    }

    @Test
    void aPermanentFailureGoesStraightToDeadLetter() {
        String type = newType();
        AtomicInteger calls = new AtomicInteger();
        long id = publishCommitted(type, CorrelationId.generate());

        assertThat(drain(IMMEDIATE, consumer(type, event -> {
            calls.incrementAndGet();
            throw new PermanentDeliveryFailure("malformed recipient");
        }))).isEqualTo(1);

        Map<String, Object> row = row(id);
        assertThat(row.get("state")).isEqualTo("DEAD_LETTER");
        assertThat(attempt(id)).isOne();
        assertThat((String) row.get("last_error")).contains("malformed recipient");
        assertThat(calls).hasValue(1);
    }

    @Test
    void whenTheLastAttemptFailsTooTheEventIsDeadLetter() {
        String type = newType();
        AtomicInteger calls = new AtomicInteger();
        OutboxConsumer alwaysFails = consumer(type, event -> {
            calls.incrementAndGet();
            throw new IllegalStateException("provider down, attempt " + (event.attempt() + 1));
        });
        long id = publishCommitted(type, CorrelationId.generate());

        assertThat(drain(IMMEDIATE, alwaysFails)).as("every retry is due at once here").isEqualTo(6);

        Map<String, Object> row = row(id);
        assertThat(row.get("state")).isEqualTo("DEAD_LETTER");
        assertThat(attempt(id)).isEqualTo(6);
        assertThat((String) row.get("last_error")).contains("provider down, attempt 6");
        assertThat(drain(IMMEDIATE, alwaysFails)).as("DEAD_LETTER is final").isZero();
        assertThat(calls).hasValue(6);
    }

    @Test
    void aRetryRunsEveryConsumerOfTheEventAgain() {
        String type = newType();
        List<String> runs = new CopyOnWriteArrayList<>();
        AtomicInteger second = new AtomicInteger();
        long id = publishCommitted(type, CorrelationId.generate());
        OutboxConsumer first = consumer(type, event -> runs.add("first"));
        OutboxConsumer failsOnce = consumer(type, event -> {
            runs.add("second");
            if (second.incrementAndGet() == 1) {
                throw new IllegalStateException("second consumer failed");
            }
        });

        drain(IMMEDIATE, first, failsOnce);
        awaitDue(id);
        drain(IMMEDIATE, first, failsOnce);

        assertThat(runs).containsExactly("first", "second", "first", "second");
        assertThat(row(id).get("state")).isEqualTo("DISPATCHED");
    }

    // ------------------------------------------------------------------------------------------ concurrency, nesting

    @Test
    void dispatchersRunningTogetherDeliverEveryEventExactlyOnce() throws Exception {
        String type = newType();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            ids.add(publishCommitted(type, CorrelationId.generate()));
        }
        List<Long> delivered = new CopyOnWriteArrayList<>();
        Set<String> deliveringThreads = ConcurrentHashMap.newKeySet();
        OutboxConsumer slow = consumer(type, event -> {
            delivered.add(event.id());
            deliveringThreads.add(Thread.currentThread().getName());
            sleep(Duration.ofMillis(25));                  // holds the row lock, so the others must skip it
        });
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            List<Future<Integer>> settled = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                settled.add(pool.submit(() -> {
                    start.await();
                    return drain(ARCH_08, slow);
                }));
            }
            start.countDown();
            int total = 0;
            for (Future<Integer> each : settled) {
                total += each.get(30, TimeUnit.SECONDS);
            }
            assertThat(total).isEqualTo(12);
        } finally {
            pool.shutdownNow();
        }

        assertThat(delivered).as("FOR UPDATE SKIP LOCKED: no event twice").containsExactlyInAnyOrderElementsOf(ids);
        assertThat(deliveringThreads).as("the dispatchers really shared the work").hasSizeGreaterThan(1);
        ids.forEach(id -> assertThat(row(id).get("state")).isEqualTo("DISPATCHED"));
    }

    @Test
    void aConsumerRunsItsOwnCommandWhileTheDispatcherHoldsTheEvent() {
        String type = newType();
        String followUp = newType();
        UUID correlation = CorrelationId.generate();
        long id = publishCommitted(type, correlation);
        long[] followUpId = new long[1];

        assertThat(drain(ARCH_08, consumer(type, event ->
                followUpId[0] = runner.run(command(context -> publish(context, followUp)))))).isEqualTo(1);

        assertThat(row(id).get("state")).isEqualTo("DISPATCHED");
        Map<String, Object> written = row(followUpId[0]);
        assertThat(written.get("state")).isEqualTo("PENDING");
        assertThat(written.get("correlation_id")).as("the consumer's command keeps the event's correlation ID")
                .isEqualTo(correlation);
    }

    // ------------------------------------------------------------------------------------------------------ order

    @Test
    void eventsGoInIdOrderAndARetryingEventDoesNotHoldBackItsAggregate() {
        String type = newType();
        UUID aggregate = UUID.randomUUID();
        List<Long> delivered = new CopyOnWriteArrayList<>();
        long firstId = publishCommittedFor(type, aggregate);
        long secondId = publishCommittedFor(type, aggregate);
        long thirdId = publishCommittedFor(type, aggregate);

        drain(ARCH_08, consumer(type, event -> {
            if (event.id() == firstId) {
                throw new IllegalStateException("first fails");
            }
            delivered.add(event.id());
        }));

        assertThat(delivered).as("PO decision 9 Oct 2026: later events are not held back")
                .containsExactly(secondId, thirdId);
        assertThat(row(firstId).get("state")).isEqualTo("PENDING");
    }

    @Test
    void anEventTypeNoConsumerTakesIsNeverPolled() {
        String waiting = newType();
        long id = publishCommitted(waiting, CorrelationId.generate());

        assertThat(drain(IMMEDIATE, consumer(newType(), event -> { }))).isZero();
        assertThat(row(id).get("state")).isEqualTo("PENDING");
        assertThat(attempt(id)).isZero();
    }

    @Test
    void theRegistryRefusesAnEventTypeTheDatabaseWouldRefuse() {
        assertThatThrownBy(() -> new ConsumerRegistry(List.of(consumer("not a type", event -> { }))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ck_outbox_event_names");
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private int drain(OutboxProperties properties, OutboxConsumer... consumers) {
        OutboxDispatcher dispatcher = new OutboxDispatcher("test", new ConsumerRegistry(List.of(consumers)), store,
                appDataSource, properties);
        int settled = 0;
        while (dispatcher.dispatchNext()) {
            settled++;
        }
        return settled;
    }

    private long publishCommitted(String type, UUID correlation) {
        try (CorrelationId.Scope ignored = CorrelationId.open(correlation)) {
            return runner.run(command(context -> publish(context, type)));
        }
    }

    /** Several events of one aggregate, each in a command of its own. */
    private long publishCommittedFor(String type, UUID aggregate) {
        try (CorrelationId.Scope ignored = CorrelationId.open(CorrelationId.generate())) {
            return runner.run(command(context -> publish(context, type, aggregate)));
        }
    }

    private long publish(CommandContext context, String type) {
        return publish(context, type, UUID.randomUUID());
    }

    private long publish(CommandContext context, String type, UUID aggregate) {
        return outbox.publish(context, new NewOutboxEvent(type, "TEST", aggregate, Map.of("aggregateId", aggregate)));
    }

    private static OutboxConsumer consumer(String type, Consumer<OutboxEvent> body) {
        return new OutboxConsumer() {
            @Override
            public Set<String> eventTypes() {
                return Set.of(type);
            }

            @Override
            public void handle(OutboxEvent event) {
                body.accept(event);
            }
        };
    }

    private static <R> Command<R> command(Function<CommandContext, R> body) {
        return new Command<>() {
            @Override
            public EligibilityCategory eligibilityCategory() {
                return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
            }

            @Override
            public CommandScope scope() {
                return CommandScope.shared(LockSet.empty());
            }

            @Override
            public R execute(CommandContext context) {
                return body.apply(context);
            }
        };
    }

    private Map<String, Object> row(long id) {
        return db().sql("SELECT * FROM technical.outbox_event WHERE id = :id").param("id", id).query().singleRow();
    }

    private Instant instant(long id, String column) {
        return db().sql("SELECT " + column + " FROM technical.outbox_event WHERE id = :id").param("id", id)
                .query(OffsetDateTime.class).single().toInstant();
    }

    private int attempt(long id) {
        return db().sql("SELECT attempt FROM technical.outbox_event WHERE id = :id").param("id", id)
                .query(Integer.class).single();
    }

    private int count(String type) {
        return db().sql("SELECT count(*) FROM technical.outbox_event WHERE event_type = :type")
                .param("type", type).query(Integer.class).single();
    }

    private Instant dbNow() {
        return db().sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single().toInstant();
    }

    /** Waits (at most a second) until the event's next attempt is due by the database clock. */
    private void awaitDue(long id) {
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (System.nanoTime() < deadline && db().sql("""
                        SELECT next_attempt_at > clock_timestamp() FROM technical.outbox_event WHERE id = :id""")
                .param("id", id).query(Boolean.class).single()) {
            Thread.onSpinWait();
        }
    }

    /** Waits (about 5 s) until the stored retry instant has come by the database clock. */
    private void makeDue(long id) {
        Instant due = instant(id, "next_attempt_at");
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline && dbNow().isBefore(due)) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private JdbcClient db() {
        return JdbcClient.create(appDataSource);
    }

    private static List<String> received(PGConnection connection, int waitMillis) throws Exception {
        List<String> channels = new ArrayList<>();
        PGNotification[] notifications = connection.getNotifications(waitMillis);
        if (notifications != null) {
            for (PGNotification notification : notifications) {
                channels.add(notification.getName());
            }
        }
        return channels;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String newType() {
        return "T" + UUID.randomUUID().toString().replace("-", "");
    }
}
