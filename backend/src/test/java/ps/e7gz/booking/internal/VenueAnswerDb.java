package ps.e7gz.booking.internal;

import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import ps.e7gz.support.PostgresTestContainer;

/**
 * Test-only reads and fixture writes for E7-47's tests, as the database owner. Reads see committed state only.
 *
 * <p>{@link #withoutTriggers} writes past every trigger ({@code session_replication_role = replica}, as
 * {@code CustomerBookingCreateHttpTest} does) to reach states no command can produce - every CHECK still applies.
 * E7-47 needs it for FR-BOK-16: {@code tr_owner_review_cascade_complete} and {@code tr_emergency_cascade_complete}
 * guarantee that no un-paused PENDING booking is left under an owner review or an unresolved emergency, so the cancel
 * branch is reachable only when that guarantee is broken (reported to the PO).
 */
final class VenueAnswerDb {

    static final JdbcClient OWNER = OnlineBookingWorld.OWNER;

    private VenueAnswerDb() {
    }

    record BookingRow(String status, String terminalReason, Instant terminalAt, String feeTreatment,
                      String rejectText, boolean counted, Instant expiresAt, long version) {
    }

    record HistoryRow(long sequence, Instant occurredAt, String fromStatus, String toStatus, String terminalReason,
                      String actorType, UUID actorId) {
    }

    record ReservationRow(boolean active, Instant releasedAt, String releaseReason) {
    }

    record JobRow(String type, String status, Instant dueAt) {
    }

    static BookingRow booking(UUID id) {
        return OWNER.sql("""
                        SELECT status, terminal_reason, terminal_at, fee_treatment, venue_reject_reason_text,
                               commitment_period IS NOT NULL AS counted, venue_response_expires_at, version
                          FROM booking.booking WHERE id = :id""")
                .param("id", id)
                .query((rs, n) -> new BookingRow(rs.getString("status"), rs.getString("terminal_reason"),
                        instant(rs.getObject("terminal_at", OffsetDateTime.class)), rs.getString("fee_treatment"),
                        rs.getString("venue_reject_reason_text"), rs.getBoolean("counted"),
                        instant(rs.getObject("venue_response_expires_at", OffsetDateTime.class)),
                        rs.getLong("version")))
                .single();
    }

    static List<HistoryRow> history(UUID id) {
        return OWNER.sql("""
                        SELECT sequence, occurred_at, from_status, to_status, terminal_reason, actor_type, actor_id
                          FROM booking.booking_history WHERE booking_id = :id ORDER BY sequence""")
                .param("id", id)
                .query((rs, n) -> new HistoryRow(rs.getLong("sequence"),
                        instant(rs.getObject("occurred_at", OffsetDateTime.class)), rs.getString("from_status"),
                        rs.getString("to_status"), rs.getString("terminal_reason"), rs.getString("actor_type"),
                        rs.getObject("actor_id", UUID.class)))
                .list();
    }

    static ReservationRow reservation(UUID reservationId) {
        return OWNER.sql("""
                        SELECT active_flag, actual_reservation_release_at, release_reason
                          FROM inventory.resource_reservation WHERE id = :id""")
                .param("id", reservationId)
                .query((rs, n) -> new ReservationRow(rs.getBoolean("active_flag"),
                        instant(rs.getObject("actual_reservation_release_at", OffsetDateTime.class)),
                        rs.getString("release_reason")))
                .single();
    }

    static Optional<JobRow> job(String key) {
        return OWNER.sql("SELECT type, status, due_at FROM technical.scheduled_job WHERE idempotency_key = :key")
                .param("key", key)
                .query((rs, n) -> new JobRow(rs.getString("type"), rs.getString("status"),
                        instant(rs.getObject("due_at", OffsetDateTime.class))))
                .optional();
    }

    static String t1(UUID bookingId) {
        return VenueAnswers.venueResponseExpiryKey(bookingId);
    }

    static String bookingStart(UUID bookingId) {
        return VenueAnswers.BOOKING_START + ":" + bookingId;
    }

    /** The payloads, as JSON text, of the outbox events of one type and aggregate. */
    static List<String> events(String type, UUID aggregateId) {
        return OWNER.sql("""
                        SELECT payload::text AS payload FROM technical.outbox_event
                         WHERE event_type = :type AND aggregate_id = :id ORDER BY id""")
                .param("type", type).param("id", aggregateId)
                .query(String.class)
                .list();
    }

    static long count(String fromWhere, UUID id) {
        return OWNER.sql("SELECT count(*) FROM " + fromWhere).param("id", id).query(Long.class).single();
    }

    /** An unresolved emergency with its selling block, as Operations would declare it; no cascade is run. */
    static void venueEmergency(UUID venueId) {
        withoutTriggers(jdbc -> jdbc.sql("""
                        WITH e AS (
                            INSERT INTO operations.emergency (id, scope, venue_id, status, declared_at, expected_end_at,
                                                              declared_period, reason)
                            VALUES (gen_random_uuid(), 'VENUE', :venue, 'ACTIVE', now(), now() + interval '10 minutes',
                                    tstzrange(now(), now() + interval '10 minutes', '[)'), 'E7-47 test')
                            RETURNING id, declared_at)
                        INSERT INTO venue.venue_selling_block (id, venue_id, cause, emergency_id, active, started_at)
                        SELECT gen_random_uuid(), :venue, 'EMERGENCY', e.id, true, e.declared_at FROM e""")
                .param("venue", venueId).update());
    }

    static void resourceEmergency(UUID venueId, UUID resourceId) {
        withoutTriggers(jdbc -> jdbc.sql("""
                        WITH e AS (
                            INSERT INTO operations.emergency (id, scope, venue_id, resource_id, status, declared_at,
                                                              expected_end_at, declared_period, reason)
                            VALUES (gen_random_uuid(), 'RESOURCE', :venue, :room, 'ACTIVE', now(),
                                    now() + interval '10 minutes', tstzrange(now(), now() + interval '10 minutes', '[)'),
                                    'E7-47 test')
                            RETURNING id, declared_at)
                        INSERT INTO resource.resource_selling_block (id, resource_id, cause, emergency_id, active,
                                                                     started_at)
                        SELECT gen_random_uuid(), :room, 'EMERGENCY', e.id, true, e.declared_at FROM e""")
                .param("venue", venueId).param("room", resourceId).update());
    }

    /** A selling block whose cause is not an Emergency (no {@code operations.emergency} row). */
    static void venueSellingBlockWithoutEmergency(UUID venueId) {
        withoutTriggers(jdbc -> jdbc.sql("""
                        INSERT INTO venue.venue_selling_block (id, venue_id, cause, active, started_at)
                        VALUES (gen_random_uuid(), :venue, 'E7-47 test block', true, now())""")
                .param("venue", venueId).update());
    }

    static void ownerReview(UUID resourceId) {
        withoutTriggers(jdbc -> jdbc.sql("""
                        UPDATE resource.resource SET operational_restriction = 'OWNER_REVIEW_REQUIRED'
                         WHERE id = :id""")
                .param("id", resourceId).update());
    }

    static void resourceDisabled(UUID resourceId) {
        withoutTriggers(jdbc -> jdbc.sql("UPDATE resource.resource SET base_state = 'DISABLED' WHERE id = :id")
                .param("id", resourceId).update());
    }

    /** FR-OPS-34: the response timer paused, with its open pause history row, as Operations would pause it. */
    static void paused(UUID bookingId) {
        withoutTriggers(jdbc -> {
            jdbc.sql("""
                            UPDATE booking.booking SET response_pause_started_at = now(),
                                   cumulative_emergency_pause_duration = interval '0'
                             WHERE id = :id""")
                    .param("id", bookingId).update();
            jdbc.sql("""
                            INSERT INTO operations.booking_timer_pause_history (id, booking_id, sequence, pause_kind,
                                                                                pause_started_at, pause_cause)
                            VALUES (gen_random_uuid(), :id, 1, 'VENUE_RESPONSE', now(), 'E7-47 test')""")
                    .param("id", bookingId).update();
            jdbc.sql("UPDATE technical.scheduled_job SET status = 'PAUSED' WHERE idempotency_key = :key")
                    .param("key", t1(bookingId)).update();
        });
    }

    /** Whether the booking's venue-response timer is paused ({@code response_pause_started_at} set). */
    static boolean responsePaused(UUID bookingId) {
        return OWNER.sql("SELECT response_pause_started_at IS NOT NULL FROM booking.booking WHERE id = :id")
                .param("id", bookingId).query(Boolean.class).single();
    }

    /** Every outbox event type written for the booking, oldest first. */
    static List<String> outboxTypes(UUID bookingId) {
        return OWNER.sql("SELECT event_type FROM technical.outbox_event WHERE aggregate_id = :id ORDER BY id")
                .param("id", bookingId).query(String.class).list();
    }

    /** Idempotency records of one key of a principal, completed or not. */
    static long idempotencyRecords(UUID principalId, String key) {
        return OWNER.sql("""
                        SELECT count(*) FROM technical.idempotency_record
                         WHERE principal_id = :id AND idempotency_key = :key""")
                .param("id", principalId).param("key", key).query(Long.class).single();
    }

    /** The emergencies of the venue end and their selling blocks lift, as Operations would end them. */
    static void endEmergencies(UUID venueId) {
        withoutTriggers(jdbc -> {
            jdbc.sql("""
                            UPDATE venue.venue_selling_block SET active = false, ended_at = now()
                             WHERE venue_id = :venue AND active""")
                    .param("venue", venueId).update();
            jdbc.sql("""
                            UPDATE resource.resource_selling_block b SET active = false, ended_at = now()
                              FROM resource.resource r
                             WHERE r.id = b.resource_id AND r.venue_id = :venue AND b.active""")
                    .param("venue", venueId).update();
            jdbc.sql("""
                            UPDATE operations.emergency SET status = 'ENDED', ended_at = now()
                             WHERE venue_id = :venue AND status IN ('ACTIVE', 'AWAITING_CONFIRMATION')""")
                    .param("venue", venueId).update();
        });
    }

    /** FR-OPS-39: the timer resumes - pause closed, T1 re-armed - as Operations would resume it. */
    static void resumed(UUID bookingId) {
        withoutTriggers(jdbc -> {
            jdbc.sql("""
                            UPDATE booking.booking
                               SET cumulative_emergency_pause_duration = now() - response_pause_started_at,
                                   response_pause_started_at = NULL
                             WHERE id = :id""")
                    .param("id", bookingId).update();
            jdbc.sql("""
                            UPDATE operations.booking_timer_pause_history
                               SET pause_ended_at = now(), accumulated_duration = now() - pause_started_at
                             WHERE booking_id = :id AND pause_ended_at IS NULL""")
                    .param("id", bookingId).update();
            jdbc.sql("UPDATE technical.scheduled_job SET status = 'PENDING' WHERE idempotency_key = :key")
                    .param("key", t1(bookingId)).update();
        });
    }

    /** TEST-ONLY data defect (D11): the PENDING booking's reservation is gone from the active ledger. */
    static void reservationLost(UUID reservationId) {
        withoutTriggers(jdbc -> jdbc.sql("""
                        UPDATE inventory.resource_reservation
                           SET active_flag = false, actual_reservation_release_at = actual_reservation_start,
                               release_reason = 'E7-47 test: lost'
                         WHERE id = :id""")
                .param("id", reservationId).update());
    }

    static void marketplaceStatus(UUID venueId, String status) {
        withoutTriggers(jdbc -> jdbc.sql("UPDATE venue.venue SET marketplace_status = :status WHERE id = :id")
                .param("status", status).param("id", venueId).update());
    }

    static void withoutTriggers(Consumer<JdbcClient> writes) {
        SingleConnectionDataSource owner = new SingleConnectionDataSource(
                PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR, true);
        try {
            JdbcClient jdbc = JdbcClient.create(owner);
            jdbc.sql("SET session_replication_role = replica").update();
            writes.accept(jdbc);
            jdbc.sql("SET session_replication_role = origin").update();
        } finally {
            owner.destroy();
        }
    }

    static Instant dbNow() {
        return OWNER.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single().toInstant();
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
