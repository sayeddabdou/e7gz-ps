package ps.e7gz.trust.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;
import static ps.e7gz.support.fixtures.Fixtures.aUser;

import java.sql.Connection;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.postgresql.Driver;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import ps.e7gz.attendance.internal.AttendanceFlows;
import ps.e7gz.booking.internal.BookingFlows;
import ps.e7gz.booking.internal.BookingFlows.Booking;
import ps.e7gz.common.codes.TrustEventType;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.boundary.TestCommands;
import ps.e7gz.trust.api.BookingOutcomes;
import ps.e7gz.trust.api.TrustReplay;
import ps.e7gz.trust.api.TrustReplay.Boundary;

/** E7-69: the real canonical SQL fold, including read-only runtime access (arch 07 section 3).
 * Correction fixtures are inspected inside commands and rolled back: E7-70 owns their reconciliation.
 * No trigger or constraint is disabled, and no incomplete correction is committed.
 */
class TrustReplayIntegrationTest extends IntegrationTest {
    @Autowired ApplicationContext context;
    @Autowired CommandRunner runner;
    @Autowired DataSource appDataSource;
    @Autowired TrustReplay replay;
    @Autowired BookingOutcomes outcomes;
    BookingFlows flows;
    AttendanceFlows attendance;
    CorrelationId.Scope correlation;

    @BeforeEach
    void setUp() {
        correlation = CorrelationId.open(CorrelationId.generate());
        flows = BookingFlows.of(context);
        attendance = AttendanceFlows.of(context);
    }

    @AfterEach
    void tearDown() {
        correlation.close();
    }

    /**
     * {@code trust.trust_event} is only ever appended (FR-TRS-01): the database refuses an update or a delete even
     * from the schema owner ({@code tr_trust_event_append_only}), so no correction can rewrite the stream replay reads.
     */
    @Test
    void aTrustEventCanBeNeitherUpdatedNorDeleted() {
        Booking missed = missed(flows.customer());
        var event = replay.resolveEffectiveOutcomes(missed.customerId(), null).getFirst();
        JdbcClient owner = JdbcClient.create(new SimpleDriverDataSource(new Driver(),
                PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

        assertThatThrownBy(() -> owner.sql("UPDATE trust.trust_event SET event_type = 'BOOKING_ATTENDED' WHERE id = :id")
                .param("id", event.eventId()).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> owner.sql("DELETE FROM trust.trust_event WHERE id = :id")
                .param("id", event.eventId()).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThat(replay.resolveEffectiveOutcomes(missed.customerId(), null)).containsExactly(event);
    }

    @Test
    void anEmptyHistoryHasTheInitialState() {
        UUID user = aUser().verified().create().id();
        var state = replay.replay(user, null);
        assertThat(state.consecutiveNoShows()).isZero();
        assertThat(state.penaltyStage()).isZero();
        assertThat(state.currentThreshold()).isEqualTo(3);
        assertThat(state.completedAttendedCount()).isZero();
        assertThat(state.lastEffectiveAt()).isNull();
        assertThat(state.lastEventSequence()).isNull();
        assertThat(replay.timeline(user, null)).isEmpty();
        assertThat(replay.resolveEffectiveOutcomes(user, null)).isEmpty();
    }

    @Test
    void committedHistoryReplaysOnAReadOnlyRuntimeConnectionWithoutChangingTheProjection() throws Exception {
        Booking missed = missed(flows.customer());
        try (Connection connection = appDataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            var reader = new JdbcTrustReplay(new SingleConnectionDataSource(connection, true));
            try (var statement = connection.createStatement(); var result = statement.executeQuery("SHOW transaction_read_only")) {
                result.next();
                assertThat(result.getString(1)).isEqualTo("on");
            }
            var state = reader.replay(missed.customerId(), null);
            assertThat(state.consecutiveNoShows()).isOne();
            assertThat(reader.replay(missed.customerId(), null)).isEqualTo(state);
            assertThat(reader.timeline(missed.customerId(), null)).hasSize(1);
            assertThat(reader.resolveEffectiveOutcomes(missed.customerId(), null))
                    .extracting(TrustReplay.Effect::type).containsExactly(TrustEventType.BOOKING_NO_SHOW);
            try (var statement = connection.prepareStatement("SELECT consecutive_no_shows, projection_version FROM trust.trust_projection WHERE user_id = ?")) {
                statement.setObject(1, missed.customerId());
                try (var result = statement.executeQuery()) {
                    result.next();
                    assertThat(result.getInt(1)).isEqualTo(state.consecutiveNoShows());
                    assertThat(result.getLong(2)).isOne();
                }
            }
            connection.rollback();
        }
    }

    /** "Seeded replay reproduces history": every past position, and the stored projection as the last one. */
    @Test
    void aSeededHistoryReplaysToEveryPastPositionAndToTheStoredProjection() {
        BookingFlows.Customer customer = flows.customer();
        Booking first = missed(customer, Duration.ofMinutes(45));
        Booking second = missed(customer);
        UUID user = first.customerId();

        var timeline = replay.timeline(user, null);
        assertThat(timeline).extracting(TrustReplay.Step::effectiveAt)
                .containsExactly(first.startAt(), second.startAt());
        assertThat(timeline).extracting(TrustReplay.Step::consecutiveNoShows).containsExactly(1, 2);
        assertThat(timeline).extracting(TrustReplay.Step::qualificationStage).containsOnlyNulls();
        assertThat(replay.replay(user, Boundary.at(first.startAt())).consecutiveNoShows()).isOne();
        assertThat(replay.replay(user, Boundary.at(second.startAt()))).isEqualTo(replay.replay(user, null));

        var state = replay.replay(user, null);
        assertThat(replay.replay(user, null)).as("deterministic").isEqualTo(state);
        assertThat(state.lastEffectiveAt()).isEqualTo(second.startAt());
        var stored = JdbcClient.create(appDataSource).sql("""
                        SELECT last_event_sequence, consecutive_no_shows, penalty_stage, current_threshold,
                               completed_attended_count, vp_qualification
                          FROM trust.trust_projection WHERE user_id = :user
                        """).param("user", user).query().singleRow();
        assertThat(stored.get("last_event_sequence")).isEqualTo(state.lastEventSequence());
        assertThat(stored.get("consecutive_no_shows")).isEqualTo(state.consecutiveNoShows());
        assertThat(((Number) stored.get("penalty_stage")).intValue()).isEqualTo(state.penaltyStage());
        assertThat(((Number) stored.get("current_threshold")).intValue()).isEqualTo(state.currentThreshold());
        assertThat(stored.get("completed_attended_count")).isEqualTo(state.completedAttendedCount());
        assertThat(stored.get("vp_qualification")).isEqualTo(state.vpQualification().name());
    }

    /**
     * The one writer's interruption outcome (arch 07 section 7.3 rule 4): effective at the booking's start, occurred
     * at the command instant, and the counter resets with no progress. Rolled back: the ACTIVE interruptions that
     * resolve attendance to ATTENDED_INTERRUPTED are Sprint 5, and the database refuses the event without it at commit.
     */
    @Test
    void anInterruptionOutcomeResetsTheCounterWithoutProgress() {
        BookingFlows.Customer customer = flows.customer();
        missed(customer);
        Booking booking = customer.nextActive(Duration.ofMinutes(5));
        rollback(booking, command -> {
            outcomes.recordInterruption(command, booking.customerId(), booking.bookingId(), booking.startAt());
            var effect = replay.resolveEffectiveOutcomes(command, booking.customerId(), null).getLast();
            assertThat(effect.type()).isEqualTo(TrustEventType.BOOKING_ATTENDED_INTERRUPTED);
            assertThat(effect.effectiveAt()).isEqualTo(booking.startAt());
            assertThat(command.jdbc().sql("SELECT occurred_at FROM trust.trust_event WHERE id = :id")
                    .param("id", effect.eventId()).query(OffsetDateTime.class).single().toInstant())
                    .isEqualTo(command.commandNow());
            var state = replay.replay(command, booking.customerId(), null);
            assertThat(state.consecutiveNoShows()).isZero();
            assertThat(state.completedAttendedCount()).isZero();
            assertThat(command.jdbc().sql("SELECT consecutive_no_shows FROM trust.trust_projection WHERE user_id = :u")
                    .param("u", booking.customerId()).query(Integer.class).single()).isZero();
        });
        assertThat(replay.replay(booking.customerId(), null).consecutiveNoShows()).isOne();
    }

    @Test
    void asOfIncludesTheExactInstantAndCanCutBetweenSequences() {
        Booking missed = missed(flows.customer());
        var effect = replay.resolveEffectiveOutcomes(missed.customerId(), null).getFirst();
        assertThat(replay.replay(missed.customerId(), Boundary.at(missed.startAt().minusNanos(1000)))
                .consecutiveNoShows()).isZero();
        assertThat(replay.replay(missed.customerId(), Boundary.at(missed.startAt())).consecutiveNoShows()).isOne();
        assertThat(replay.replay(missed.customerId(), new Boundary(missed.startAt(), effect.eventSequence() - 1))
                .consecutiveNoShows()).isZero();
        assertThat(replay.replay(missed.customerId(), new Boundary(missed.startAt(), effect.eventSequence()))
                .consecutiveNoShows()).isOne();
    }

    @Test
    void aLaterReversalChangesTheOriginalPositionAndNotTheCorrectionTime() {
        BookingFlows.Customer customer = flows.customer();
        Booking first = missed(customer, Duration.ofMinutes(45));
        Booking second = missed(customer);
        var original = replay.resolveEffectiveOutcomes(first.customerId(), null).getFirst();
        UUID secondEvent = replay.resolveEffectiveOutcomes(second.customerId(), null).getLast().eventId();
        rollback(first, command -> {
            UUID reversed = correction(command, first, original.eventId(), null, null);
            var effects = replay.resolveEffectiveOutcomes(command, first.customerId(), null);
            assertThat(effects).extracting(TrustReplay.Effect::eventId)
                    .containsExactly(reversed, secondEvent);
            assertThat(effects.getFirst().effectiveAt()).isEqualTo(first.startAt());
            assertThat(replay.replay(command, first.customerId(), Boundary.at(first.startAt())).consecutiveNoShows()).isZero();
            assertThat(replay.replay(command, first.customerId(), null).consecutiveNoShows()).isOne();
            assertThat(replay.replay(command, first.customerId(), new Boundary(first.startAt(), original.eventSequence()))
                    .consecutiveNoShows()).isOne();
        });
        assertThat(replay.replay(first.customerId(), null).consecutiveNoShows()).isEqualTo(2);
    }

    @Test
    void threeCorrectionsResolveToOneOutcomeAndOneReset() {
        Booking missed = missed(flows.customer());
        var original = replay.resolveEffectiveOutcomes(missed.customerId(), null).getFirst();
        rollback(missed, command -> {
            UUID first = correction(command, missed, original.eventId(), null, "ATTENDED");
            UUID second = correction(command, missed, original.eventId(), first, "ABSENT_NO_SHOW");
            UUID third = correction(command, missed, original.eventId(), second, "ATTENDED_INTERRUPTED");
            var effects = replay.resolveEffectiveOutcomes(command, missed.customerId(), null);
            assertThat(effects).hasSize(1);
            assertThat(effects.getFirst().eventId()).isEqualTo(third);
            assertThat(effects.getFirst().type()).isEqualTo(TrustEventType.BOOKING_ATTENDED_INTERRUPTED);
            var state = replay.replay(command, missed.customerId(), null);
            assertThat(state.consecutiveNoShows()).isZero();
            assertThat(state.completedAttendedCount()).isZero();
            assertThat(replay.replay(command, missed.customerId(), null)).isEqualTo(state);
        });
    }

    @Test
    void j3AnAttendedOutcomeAfterANoShowResetsTheCounterAndAddsOneProgress() {
        BookingFlows.Customer customer = flows.customer();
        missed(customer);
        Booking second = missed(customer);
        var original = replay.resolveEffectiveOutcomes(second.customerId(), null).getLast();
        rollback(second, command -> {
            correction(command, second, original.eventId(), null, "ATTENDED");
            var timeline = replay.timeline(command, second.customerId(), null);
            assertThat(timeline).extracting(TrustReplay.Step::consecutiveNoShows).containsExactly(1, 0);
            assertThat(timeline).extracting(TrustReplay.Step::completedAttendedCount).containsExactly(0, 1);
        });
    }

    /** Real committed no-shows precede a new canonical outcome in effective order. The pending outcome is
     * rolled back because this test exercises pure replay, not completion/interruption/ban reconciliation.
     */
    @ParameterizedTest
    @CsvSource({"BOOKING_ATTENDED,0,1,0,3", "BOOKING_ATTENDED_INTERRUPTED,0,0,0,3",
            "BOOKING_ACCOUNT_DISABLE_ATTENDANCE_PROTECTION,0,0,0,3",
            "BOOKING_ATTENDANCE_UNCONFIRMED,2,0,0,3", "BOOKING_NO_SHOW,0,0,1,2"})
    void canonicalOutcomesFoldThePriorCounter(String type, int counter, int progress, int stage, int threshold) {
        BookingFlows.Customer customer = flows.customer();
        missed(customer);
        missed(customer);
        Booking third = customer.nextActive(Duration.ofMinutes(5));
        rollback(third, command -> {
            input(command, third, type);
            var state = replay.replay(command, third.customerId(), null);
            assertThat(state.consecutiveNoShows()).isEqualTo(counter);
            assertThat(state.completedAttendedCount()).isEqualTo(progress);
            assertThat(state.penaltyStage()).isEqualTo(stage);
            assertThat(state.currentThreshold()).isEqualTo(threshold);
            assertThat(replay.timeline(command, third.customerId(), null).getLast().qualificationStage())
                    .isEqualTo(stage == 1 ? 1 : null);
        });
        assertThat(replay.replay(third.customerId(), null).consecutiveNoShows()).isEqualTo(2);
    }

    @Test
    void protectionSurvivesUnconfirmedAttendanceButIsNeutralisedByAnAttendedCorrection() {
        BookingFlows.Customer customer = flows.customer();
        missed(customer);
        Booking booking = customer.nextActive(Duration.ofMinutes(5));
        rollback(booking, command -> {
            input(command, booking, "BOOKING_ACCOUNT_DISABLE_ATTENDANCE_PROTECTION");
            UUID original = input(command, booking, "BOOKING_ATTENDANCE_UNCONFIRMED");
            assertThat(replay.resolveEffectiveOutcomes(command, booking.customerId(), null))
                    .extracting(TrustReplay.Effect::type).containsExactly(TrustEventType.BOOKING_NO_SHOW,
                            TrustEventType.BOOKING_ACCOUNT_DISABLE_ATTENDANCE_PROTECTION,
                            TrustEventType.BOOKING_ATTENDANCE_UNCONFIRMED);
            assertThat(replay.replay(command, booking.customerId(), null).consecutiveNoShows()).isZero();
            correction(command, booking, original, null, "ATTENDED");
            assertThat(replay.resolveEffectiveOutcomes(command, booking.customerId(), null))
                    .extracting(TrustReplay.Effect::type).containsExactly(TrustEventType.BOOKING_NO_SHOW,
                            TrustEventType.BOOKING_ATTENDED);
            assertThat(replay.replay(command, booking.customerId(), null).completedAttendedCount()).isOne();
        });
    }

    @Test
    void absoluteAdminAdjustmentAndEqualTimeSequenceBoundariesUseTheCanonicalFold() {
        Booking booking = missed(flows.customer());
        rollback(booking, command -> {
            UUID adjustment = UUID.randomUUID();
            command.jdbc().sql("""
                    INSERT INTO trust.trust_event (id,event_type,subject_user_id,occurred_at,effective_at,payload)
                    VALUES (:id,'ADMIN_TRUST_ADJUSTMENT',:user,:now,:now,
                      jsonb_build_object('schemaVersion',1,'counterAfter',1,'penaltyStageAfter',2,
                                         'adminId',CAST(:admin AS text),'correlationId',CAST(:correlation AS text)))
                    """)
                    .param("id", adjustment).param("user", booking.customerId())
                    .param("now", OffsetDateTime.ofInstant(command.commandNow(), ZoneOffset.UTC))
                    .param("admin", UUID.randomUUID()).param("correlation", CorrelationId.require()).update();
            var effect = replay.resolveEffectiveOutcomes(command, booking.customerId(), null).getLast();
            assertThat(effect.eventId()).isEqualTo(adjustment);
            assertThat(effect.type()).isEqualTo(TrustEventType.ADMIN_TRUST_ADJUSTMENT);
            var before = replay.replay(command, booking.customerId(),
                    new Boundary(effect.effectiveAt(), effect.eventSequence() - 1));
            var after = replay.replay(command, booking.customerId(), Boundary.at(effect.effectiveAt()));
            assertThat(before.penaltyStage()).isZero();
            assertThat(after.penaltyStage()).isEqualTo(2);
            assertThat(after.consecutiveNoShows()).isOne();
            assertThat(after.currentThreshold()).isEqualTo(2);
            assertThat(replay.timeline(command, booking.customerId(), null).getLast().qualificationStage()).isNull();
        });
    }

    @Test
    void aConcurrentReaderNeverSeesAnUncommittedCorrection() throws Exception {
        Booking booking = missed(flows.customer());
        UUID original = replay.resolveEffectiveOutcomes(booking.customerId(), null).getFirst().eventId();
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var writer = executor.submit(() -> rollback(booking, command -> {
                correction(command, booking, original, null, "ATTENDED");
                assertThat(replay.replay(command, booking.customerId(), null).consecutiveNoShows()).isZero();
                inserted.countDown();
                try {
                    assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            try {
                assertThat(inserted.await(20, TimeUnit.SECONDS)).isTrue();
                assertThat(replay.replay(booking.customerId(), null).consecutiveNoShows()).isOne();
                assertThat(replay.resolveEffectiveOutcomes(booking.customerId(), null))
                        .extracting(TrustReplay.Effect::eventId).containsExactly(original);
            } finally {
                release.countDown();
            }
            writer.get(20, TimeUnit.SECONDS);
        }
    }

    private Booking missed(BookingFlows.Customer customer) {
        return missed(customer, Duration.ofMinutes(20));
    }

    private Booking missed(BookingFlows.Customer customer, Duration startedAgo) {
        Booking booking = customer.nextActive(startedAgo);
        attendance.noShow(booking.venueAccountId(), booking.bookingId());
        return booking;
    }

    private void rollback(Booking booking, Consumer<CommandContext> body) {
        var scope = CommandScope.shared(LockSet.builder().users(booking.customerId()).venues(booking.venueId())
                .resources(booking.resourceId()).build());
        assertThatThrownBy(() -> runner.run(TestCommands.under(scope, command -> {
            body.accept(command);
            throw new FixtureRollback();
        }))).isExactlyInstanceOf(FixtureRollback.class);
    }

    private UUID correction(CommandContext command, Booking booking, UUID original, UUID previous, String outcome) {
        UUID id = UUID.randomUUID();
        command.jdbc().sql("""
                INSERT INTO trust.trust_event
                    (id,event_type,subject_user_id,occurred_at,effective_at,booking_id,
                     original_outcome_event_id,superseded_correction_event_id,corrected_outcome)
                VALUES (:id,:type,:user,:now,:start,:booking,:original,:previous,:outcome)
                """)
                .param("id", id).param("type", outcome == null ? "NO_SHOW_REVERSED" : "ATTENDANCE_CORRECTED")
                .param("user", booking.customerId()).param("now", OffsetDateTime.ofInstant(command.commandNow(), ZoneOffset.UTC))
                .param("start", OffsetDateTime.ofInstant(booking.startAt(), ZoneOffset.UTC)).param("booking", booking.bookingId())
                .param("original", original).param("previous", previous, java.sql.Types.OTHER)
                .param("outcome", outcome, java.sql.Types.VARCHAR).update();
        return id;
    }

    private UUID input(CommandContext command, Booking booking, String type) {
        UUID id = UUID.randomUUID();
        command.jdbc().sql(JdbcBookingOutcomes.INSERT_OUTCOME)
                .param("id", id).param("type", type).param("userId", booking.customerId())
                .param("occurredAt", OffsetDateTime.ofInstant(command.commandNow(), ZoneOffset.UTC))
                .param("effectiveAt", OffsetDateTime.ofInstant(booking.startAt(), ZoneOffset.UTC))
                .param("bookingId", booking.bookingId()).update();
        return id;
    }

    private static final class FixtureRollback extends RuntimeException { }
}
