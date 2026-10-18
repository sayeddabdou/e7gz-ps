package ps.e7gz.booking.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * TEST-ONLY: puts an Online Booking made by the real creation command into a state, at instants the test chooses,
 * that no command of Sprint 2 can produce yet (AWAITING_CUSTOMER_CONFIRMATION, NO_SHOW, COMPLETED, a cancellation at
 * the start) or that lies hours in the past - as the E7-46 / E7-47 retention tests do. It writes past every trigger
 * ({@link VenueAnswerDb#withoutTriggers}); every CHECK still applies, so each planted row has the shape the database
 * accepts: one terminal reason, its fee treatment, {@code terminal_at}, and a commitment period only while it counts.
 * A booking that no longer holds its room has its reservation released, so no two active reservations ever overlap
 * (the integrity sweep stays clean).
 *
 * <p>A planted REJECTED or CANCELLED booking has no Booking Fee decision: {@code loyalty.booking_fee_refund} is
 * producer-only (ADR-026). Loyalty re-checks every booking of a Customer at the commit of that Customer's next Loyalty
 * command ({@code loyalty.assert_booking}: "Terminal Booking fee decision omitted"), so plant those states last for a
 * Customer - after every command that Customer still has to run.
 */
final class BookingStates {

    private BookingStates() {
    }

    /**
     * What to plant. Null members are left out: no move of the interval, no expiry or Late Confirm history row, no
     * terminal instant.
     *
     * @param startAt    the new start; the end keeps the booked duration
     * @param expiredAt  a PENDING -> VENUE_RESPONSE_EXPIRED history row at this instant
     * @param reopenedAt a VENUE_RESPONSE_EXPIRED -> AWAITING_CUSTOMER_CONFIRMATION history row (a Late Confirm)
     * @param terminalAt {@code terminal_at}, and the final transition's history row, when the status is terminal
     */
    record Plant(String status, String reason, Instant startAt, Instant expiredAt, Instant reopenedAt,
                 Instant terminalAt) {

        static Plant of(String status) {
            return new Plant(status, null, null, null, null, null);
        }

        Plant reason(String value) {
            return new Plant(status, value, startAt, expiredAt, reopenedAt, terminalAt);
        }

        Plant startAt(Instant value) {
            return new Plant(status, reason, value, expiredAt, reopenedAt, terminalAt);
        }

        Plant expiredAt(Instant value) {
            return new Plant(status, reason, startAt, value, reopenedAt, terminalAt);
        }

        Plant reopenedAt(Instant value) {
            return new Plant(status, reason, startAt, expiredAt, value, terminalAt);
        }

        Plant terminalAt(Instant value) {
            return new Plant(status, reason, startAt, expiredAt, reopenedAt, value);
        }
    }

    static void plant(UUID bookingId, Plant plant) {
        VenueAnswerDb.withoutTriggers(jdbc -> {
            if (plant.startAt() != null) {
                jdbc.sql("""
                                UPDATE booking.booking
                                   SET end_at = :start + (end_at - start_at), start_at = :start,
                                       commitment_period = CASE WHEN commitment_period IS NOT NULL
                                           THEN tstzrange(:start, :start + (end_at - start_at), '[)') END
                                 WHERE id = :id""")
                        .param("start", utc(plant.startAt())).param("id", bookingId).update();
            }
            String previous = "PENDING";
            if (plant.expiredAt() != null) {
                history(jdbc, bookingId, plant.expiredAt(), previous, "VENUE_RESPONSE_EXPIRED", null);
                previous = "VENUE_RESPONSE_EXPIRED";
            }
            if (plant.reopenedAt() != null) {
                history(jdbc, bookingId, plant.reopenedAt(), previous, "AWAITING_CUSTOMER_CONFIRMATION", null);
                previous = "AWAITING_CUSTOMER_CONFIRMATION";
            }
            if (plant.terminalAt() != null) {
                history(jdbc, bookingId, plant.terminalAt(), previous, plant.status(), plant.reason());
            }
            jdbc.sql("""
                            UPDATE booking.booking
                               SET status = :status, terminal_reason = CAST(:reason AS text),
                                   terminal_at = CAST(:terminalAt AS timestamptz),
                                   fee_treatment = CASE WHEN CAST(:reason AS text) IS NULL THEN NULL
                                                        ELSE booking.expected_fee_treatment(CAST(:reason AS text)) END,
                                   venue_reject_reason_text = CASE WHEN CAST(:reason AS text) = 'VENUE_REJECTED'
                                                                   THEN 'E7-49 test' END,
                                   response_pause_started_at = NULL,
                                   commitment_period = CASE
                                       WHEN source = 'ONLINE' AND CAST(:status AS text) IN ('PENDING',
                                            'AWAITING_CUSTOMER_CONFIRMATION', 'CONFIRMED',
                                                        'ACTIVE') THEN tstzrange(start_at, end_at, '[)') END
                             WHERE id = :id""")
                    .param("status", plant.status()).param("reason", plant.reason())
                    .param("terminalAt", plant.terminalAt() == null ? null : utc(plant.terminalAt()))
                    .param("id", bookingId).update();
            if (!holdsItsRoom(plant.status())) {
                jdbc.sql("""
                                UPDATE inventory.resource_reservation
                                   SET active_flag = false, actual_reservation_release_at = actual_reservation_start,
                                       release_reason = 'E7-49 test'
                                 WHERE booking_id = :id AND active_flag""")
                        .param("id", bookingId).update();
            }
        });
    }

    /**
     * E7-59: a CONFIRMED Online Booking moved to ACTIVE, as the booking start (T4, E7-60) would move it, with its
     * interval moved to begin at {@code startAt} - the booking, its commitment and its BOOKING reservation together, so
     * the reservation still covers exactly the booked interval (the no-show release checks it). The CONFIRMED -> ACTIVE
     * history row is dated {@code startAt}. The caller withdraws the Accept's T4 job first, which would start it again.
     */
    static void started(UUID bookingId, Instant startAt) {
        VenueAnswerDb.withoutTriggers(jdbc -> {
            moveInterval(jdbc, bookingId, startAt, "ACTIVE");
            history(jdbc, bookingId, startAt, "CONFIRMED", "ACTIVE", null);
        });
    }

    /**
     * The delayed-start race (Amr Heiba, 17 Oct 2026): a CONFIRMED Online Booking whose interval is moved to begin at
     * {@code startAt}, in the past, and that stays CONFIRMED - as when its T4 (E7-60) has not been processed yet. The
     * booking, its commitment and its BOOKING reservation move together; its BOOKING_START job is left as it is.
     */
    static void startOverdue(UUID bookingId, Instant startAt) {
        VenueAnswerDb.withoutTriggers(jdbc -> {
            moveInterval(jdbc, bookingId, startAt, "CONFIRMED");
            // the room was reserved before the start, as an Accept made in time reserves it: a start-time cancellation
            // releases it at startAt, never before it was taken (ck_reservation_release_chronology)
            jdbc.sql("""
                            UPDATE inventory.resource_reservation
                               SET actual_reservation_start = LEAST(actual_reservation_start, CAST(:start AS timestamptz))
                             WHERE booking_id = :id AND reservation_type = 'BOOKING' AND active_flag""")
                    .param("start", utc(startAt)).param("id", bookingId).update();
        });
    }

    private static void moveInterval(org.springframework.jdbc.core.simple.JdbcClient jdbc, UUID bookingId,
                                     Instant startAt, String status) {
        jdbc.sql("""
                        UPDATE booking.booking
                           SET end_at = :start + (end_at - start_at), start_at = :start, status = :status,
                               commitment_period = tstzrange(:start, :start + (end_at - start_at), '[)')
                         WHERE id = :id AND status = 'CONFIRMED'""")
                .param("start", utc(startAt)).param("status", status).param("id", bookingId).update();
        jdbc.sql("""
                        UPDATE inventory.resource_reservation
                           SET planned_end = :start + (planned_end - planned_start), planned_start = :start,
                               planned_range = tstzrange(:start, :start + (planned_end - planned_start), '[)')
                         WHERE booking_id = :id AND reservation_type = 'BOOKING' AND active_flag""")
                .param("start", utc(startAt)).param("id", bookingId).update();
    }

    static Instant minutesFromNow(long minutes) {
        return VenueAnswerDb.dbNow().plus(Duration.ofMinutes(minutes));
    }

    private static boolean holdsItsRoom(String status) {
        return status.equals("PENDING") || status.equals("CONFIRMED") || status.equals("ACTIVE");
    }

    private static void history(org.springframework.jdbc.core.simple.JdbcClient jdbc, UUID bookingId, Instant at,
                                String from, String to, String reason) {
        jdbc.sql("""
                        INSERT INTO booking.booking_history (id, booking_id, sequence, occurred_at, entry_kind,
                                                             from_status, to_status, terminal_reason, actor_type,
                                                             correlation_id)
                        SELECT gen_random_uuid(), :id, COALESCE(max(h.sequence), 0) + 1, :at, 'STATUS_TRANSITION',
                               :from, :to, CAST(:reason AS text), 'SYSTEM', gen_random_uuid()
                          FROM booking.booking_history h WHERE h.booking_id = :id""")
                .param("id", bookingId).param("at", utc(at)).param("from", from).param("to", to)
                .param("reason", reason).update();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
