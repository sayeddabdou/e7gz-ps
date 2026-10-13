package ps.e7gz.booking.internal;

import static ps.e7gz.booking.internal.VenueAnswerDb.OWNER;
import static ps.e7gz.booking.internal.VenueAnswerDb.t1;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.scheduling.api.JobOutcome;
import ps.e7gz.scheduling.api.ScheduledJob;
import ps.e7gz.support.boundary.Deadlines;
import ps.e7gz.support.fixtures.Fixtures;

/**
 * TEST-ONLY for E7-58: real VENUE_RESPONSE_EXPIRED bookings - made by the real creation command and moved by the real
 * T1 command ({@link VenueAnswers#expire}) with its stored deadline moved to that command's own instant - and committed
 * reads of what a Late Confirmation writes, as the database owner.
 */
final class LateConfirmDb {

    private final CommandRunner runner;
    private final OnlineBookingCreation creation;
    private final VenueAnswerStore store;
    private final VenueAnswers answers;

    LateConfirmDb(CommandRunner runner, OnlineBookingCreation creation, VenueAnswerStore store, VenueAnswers answers) {
        this.runner = runner;
        this.creation = creation;
        this.store = store;
        this.answers = answers;
    }

    /** A real Online Booking of {@code customerId}, then expired by T1: VENUE_RESPONSE_EXPIRED, its room released. */
    UUID expired(OnlineBookingWorld world, UUID customerId, Fixtures.Resource room, Instant start, int minutes) {
        CreatedBooking created = creation.create(world.request(customerId, room, start, minutes));
        JobOutcome outcome = runner.run(command(created.bookingId(), context -> {
            Deadlines.move(context, "booking.booking.venue_response_expires_at", created.bookingId(),
                    context.commandNow());
            return answers.expire(context, new ScheduledJob(UUID.randomUUID(),
                    CreateOnlineBookingCommand.VENUE_RESPONSE_EXPIRY, CreateOnlineBookingCommand.BOOKING_SUBJECT,
                    created.bookingId(), created.venueResponseExpiresAt(), 1, t1(created.bookingId()),
                    CorrelationId.generate()));
        }));
        if (!(outcome instanceof JobOutcome.Done)) {
            throw new IllegalStateException("T1 did not expire " + created.bookingId() + ": " + outcome);
        }
        return created.bookingId();
    }

    UUID expired(OnlineBookingWorld world, Instant start, int minutes) {
        return expired(world, world.customer.id(), world.room, start, minutes);
    }

    private <R> Command<R> command(UUID bookingId, java.util.function.Function<CommandContext, R> body) {
        CommandScope scope = runner.run(new FindBooking(store, bookingId)).orElseThrow().scope();
        return new Command<>() {
            @Override
            public EligibilityCategory eligibilityCategory() {
                return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
            }

            @Override
            public CommandScope scope() {
                return scope;
            }

            @Override
            public R execute(CommandContext context) {
                return body.apply(context);
            }
        };
    }

    // ---------------------------------------------------------------- committed reads

    record Attempt(UUID id, int attemptNo, Instant initiatedAt, Instant expiresAt, UUID holdId, String response,
                   Instant respondedAt, Instant closedAt) {
    }

    record Reservation(UUID id, String type, boolean active, Instant plannedStart, Instant plannedEnd,
                       Instant actualStart, Instant releasedAt, String releaseReason) {
    }

    record Event(long sequence, Instant occurredAt, String entryKind, String fromStatus, String toStatus,
                 String terminalReason, String eventType, String actorType, UUID actorId) {
    }

    record Amounts(java.math.BigDecimal venuePrice, java.math.BigDecimal fee, java.math.BigDecimal total,
                   Instant startAt, Instant endAt, Instant createdAt) {
    }

    static Optional<Attempt> attempt(UUID bookingId) {
        return OWNER.sql("""
                        SELECT id, attempt_no, initiated_at, expires_at, hold_reservation_id, customer_response,
                               responded_at, closed_at
                          FROM booking.late_confirmation WHERE booking_id = :id ORDER BY attempt_no DESC LIMIT 1""")
                .param("id", bookingId)
                .query((rs, n) -> new Attempt(rs.getObject("id", UUID.class), rs.getInt("attempt_no"),
                        instant(rs.getObject("initiated_at", OffsetDateTime.class)),
                        instant(rs.getObject("expires_at", OffsetDateTime.class)),
                        rs.getObject("hold_reservation_id", UUID.class), rs.getString("customer_response"),
                        instant(rs.getObject("responded_at", OffsetDateTime.class)),
                        instant(rs.getObject("closed_at", OffsetDateTime.class))))
                .optional();
    }

    static long attempts(UUID bookingId) {
        return VenueAnswerDb.count("booking.late_confirmation WHERE booking_id = :id", bookingId);
    }

    static List<Reservation> reservations(UUID bookingId) {
        return OWNER.sql("""
                        SELECT id, reservation_type, active_flag, planned_start, planned_end, actual_reservation_start,
                               actual_reservation_release_at, release_reason
                          FROM inventory.resource_reservation WHERE booking_id = :id
                         ORDER BY actual_reservation_start, id""")
                .param("id", bookingId)
                .query((rs, n) -> new Reservation(rs.getObject("id", UUID.class), rs.getString("reservation_type"),
                        rs.getBoolean("active_flag"), instant(rs.getObject("planned_start", OffsetDateTime.class)),
                        instant(rs.getObject("planned_end", OffsetDateTime.class)),
                        instant(rs.getObject("actual_reservation_start", OffsetDateTime.class)),
                        instant(rs.getObject("actual_reservation_release_at", OffsetDateTime.class)),
                        rs.getString("release_reason")))
                .list();
    }

    static List<Event> history(UUID bookingId) {
        return OWNER.sql("""
                        SELECT sequence, occurred_at, entry_kind, from_status, to_status, terminal_reason, event_type,
                               actor_type, actor_id
                          FROM booking.booking_history WHERE booking_id = :id ORDER BY sequence""")
                .param("id", bookingId)
                .query((rs, n) -> new Event(rs.getLong("sequence"),
                        instant(rs.getObject("occurred_at", OffsetDateTime.class)), rs.getString("entry_kind"),
                        rs.getString("from_status"), rs.getString("to_status"), rs.getString("terminal_reason"),
                        rs.getString("event_type"), rs.getString("actor_type"), rs.getObject("actor_id", UUID.class)))
                .list();
    }

    static Amounts amounts(UUID bookingId) {
        return OWNER.sql("""
                        SELECT venue_price_total, booking_fee_snapshot, total_amount, start_at, end_at, created_at
                          FROM booking.booking WHERE id = :id""")
                .param("id", bookingId)
                .query((rs, n) -> new Amounts(rs.getBigDecimal("venue_price_total"),
                        rs.getBigDecimal("booking_fee_snapshot"), rs.getBigDecimal("total_amount"),
                        instant(rs.getObject("start_at", OffsetDateTime.class)),
                        instant(rs.getObject("end_at", OffsetDateTime.class)),
                        instant(rs.getObject("created_at", OffsetDateTime.class))))
                .single();
    }

    static String t2(UUID bookingId) {
        return "LATE_CONFIRM_EXPIRY:" + bookingId;
    }

    static String t3(UUID bookingId) {
        return "LATE_CONFIRM_WARNING:" + bookingId;
    }

    /**
     * TEST-ONLY: an emergency pauses the AWAITING booking's Late Confirm window now - the booking's pause columns, the open
     * {@code LATE_CONFIRM} pause row and T2/T3 PAUSED - as E7-83's pause will write them.
     */
    static void lateConfirmPaused(UUID bookingId) {
        VenueAnswerDb.withoutTriggers(jdbc -> {
            jdbc.sql("""
                            UPDATE booking.booking SET late_confirm_pause_started_at = now(),
                                   cumulative_late_confirm_emergency_pause_duration = interval '0'
                             WHERE id = :id""")
                    .param("id", bookingId).update();
            jdbc.sql("""
                            INSERT INTO operations.booking_timer_pause_history (id, booking_id, sequence, pause_kind,
                                                                                pause_started_at, pause_cause)
                            VALUES (gen_random_uuid(), :id, 1, 'LATE_CONFIRM', now(), 'E7-58 test')""")
                    .param("id", bookingId).update();
            jdbc.sql("UPDATE technical.scheduled_job SET status = 'PAUSED' WHERE idempotency_key IN (:t2, :t3)")
                    .param("t2", t2(bookingId)).param("t3", t3(bookingId)).update();
        });
    }

    /** The open {@code LATE_CONFIRM} pause rows of the booking. */
    static long openLateConfirmPauses(UUID bookingId) {
        return VenueAnswerDb.count("operations.booking_timer_pause_history WHERE booking_id = :id "
                + "AND pause_kind = 'LATE_CONFIRM' AND pause_ended_at IS NULL", bookingId);
    }

    /**
     * TEST-ONLY: the stored deadline of the open attempt is put a millisecond after its initiation, and the test waits
     * until the database clock has passed it - "now" is then after the deadline, which T2 (a later phase) would own.
     */
    static void deadlinePassed(UUID bookingId) {
        Attempt attempt = attempt(bookingId).orElseThrow();
        Instant deadline = attempt.initiatedAt().plusMillis(1);
        VenueAnswerDb.withoutTriggers(jdbc -> jdbc.sql(
                        "UPDATE booking.late_confirmation SET expires_at = :at WHERE id = :id")
                .param("at", java.time.OffsetDateTime.ofInstant(deadline, java.time.ZoneOffset.UTC))
                .param("id", attempt.id()).update());
        Deadlines.awaitDatabaseClockPast(deadline);
    }

    /**
     * TEST-ONLY: the open attempt's stored deadline becomes {@code expiresAt} (still after its initiation and not after
     * the start). The database lets an open deadline only move later ({@code guard_late_confirmation_mutation}), so a
     * test that needs an earlier one sets it here, with the triggers off, as if the attempt had been made with a shorter
     * window - then, inside its command, moves it later through the real guard ({@code Deadlines.move}).
     */
    static void deadlineAt(UUID bookingId, Instant expiresAt) {
        UUID id = attempt(bookingId).orElseThrow().id();
        VenueAnswerDb.withoutTriggers(jdbc -> jdbc.sql(
                        "UPDATE booking.late_confirmation SET expires_at = :at WHERE id = :id")
                .param("at", java.time.OffsetDateTime.ofInstant(expiresAt, java.time.ZoneOffset.UTC))
                .param("id", id).update());
    }

    /** The open attempt's id - the row {@code Deadlines.move} names. */
    static UUID attemptId(UUID bookingId) {
        return attempt(bookingId).orElseThrow().id();
    }

    /** The {@code warning_sent_at} of the booking's latest attempt, or null. */
    static Instant warningSentAt(UUID bookingId) {
        return OWNER.sql("""
                        SELECT warning_sent_at FROM booking.late_confirmation
                         WHERE booking_id = :id ORDER BY attempt_no DESC LIMIT 1""")
                .param("id", bookingId)
                .query((rs, n) -> Optional.ofNullable(instant(rs.getObject("warning_sent_at", OffsetDateTime.class))))
                .list().stream().findFirst().flatMap(found -> found).orElse(null);
    }

    // ---------------------------------------------------------------- TEST-ONLY states no command of this task makes

    /** The area is deactivated (as the admin's deactivation would leave it, without its cascade). */
    static void areaInactive(UUID areaId) {
        VenueAnswerDb.withoutTriggers(jdbc -> jdbc.sql("UPDATE geo.area SET is_active = false WHERE id = :id")
                .param("id", areaId).update());
    }

    /** Every Operating Session of the venue now covers {@code [start, end)} (an opening-hours change, no cascade). */
    static void sessionPeriod(UUID venueId, Instant start, Instant end) {
        VenueAnswerDb.withoutTriggers(jdbc -> jdbc.sql("""
                        UPDATE venue.operating_session
                           SET session_period = tstzrange(:start, :end, '[)')
                         WHERE venue_id = :venue""")
                .param("start", java.time.OffsetDateTime.ofInstant(start, java.time.ZoneOffset.UTC))
                .param("end", java.time.OffsetDateTime.ofInstant(end, java.time.ZoneOffset.UTC))
                .param("venue", venueId).update());
    }

    /** The venue account is DISABLED, without E7-78's cascade (which does not exist yet). */
    static void accountDisabled(UUID venueAccountId) {
        VenueAnswerDb.withoutTriggers(jdbc -> jdbc.sql(
                        "UPDATE iam.venue_account SET account_status = 'DISABLED' WHERE id = :id")
                .param("id", venueAccountId).update());
    }

    /**
     * An unresolved RESOURCE emergency whose declared interval covers the booking, with no selling block in force -
     * availability check 5 (a declared emergency interval) fails while checks 1-4 pass.
     */
    static void emergencyIntervalOnly(UUID venueId, UUID resourceId, Instant until) {
        VenueAnswerDb.withoutTriggers(jdbc -> jdbc.sql("""
                        INSERT INTO operations.emergency (id, scope, venue_id, resource_id, status, declared_at,
                                                          expected_end_at, declared_period, reason)
                        VALUES (gen_random_uuid(), 'RESOURCE', :venue, :room, 'ACTIVE', now(), :until,
                                tstzrange(now(), :until, '[)'), 'E7-58 test')""")
                .param("venue", venueId).param("room", resourceId)
                .param("until", java.time.OffsetDateTime.ofInstant(until, java.time.ZoneOffset.UTC)).update());
    }

    /** TEST-ONLY data defect: the AWAITING booking's hold is no longer active. */
    static void holdLost(UUID bookingId) {
        UUID hold = attempt(bookingId).orElseThrow().holdId();
        VenueAnswerDb.withoutTriggers(jdbc -> jdbc.sql("""
                        UPDATE inventory.resource_reservation
                           SET active_flag = false, actual_reservation_release_at = now(),
                               release_reason = 'E7-58 test'
                         WHERE id = :id""")
                .param("id", hold).update());
    }

    /**
     * TEST-ONLY data defect (R1): the AWAITING booking's attempt is closed with no response, as no command closes it
     * while the booking still awaits ({@code tr_lc_integrity_from_booking} would refuse it). The hold stays active.
     */
    static void attemptLost(UUID bookingId) {
        UUID id = attempt(bookingId).orElseThrow().id();
        VenueAnswerDb.withoutTriggers(jdbc -> jdbc.sql(
                        "UPDATE booking.late_confirmation SET closed_at = initiated_at WHERE id = :id")
                .param("id", id).update());
    }

    /**
     * The booking expired {@code hours} ago: its VENUE_RESPONSE_EXPIRED history row is dated back, so the phone
     * retention point (expiry + 2 h, E7-49 D1) has passed.
     */
    static void expiredHoursAgo(UUID bookingId, int hours) {
        VenueAnswerDb.withoutTriggers(jdbc -> jdbc.sql("""
                        UPDATE booking.booking_history SET occurred_at = now() - make_interval(hours => :hours)
                         WHERE booking_id = :id AND to_status = 'VENUE_RESPONSE_EXPIRED'""")
                .param("hours", hours).param("id", bookingId).update());
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
