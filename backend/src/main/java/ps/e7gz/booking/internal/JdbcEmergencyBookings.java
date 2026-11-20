package ps.e7gz.booking.internal;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ps.e7gz.booking.api.AttendanceBookings;
import ps.e7gz.booking.api.InterruptionAttendance;
import ps.e7gz.booking.api.LateConfirmBookings;
import ps.e7gz.booking.api.LateConfirmClosures;
import ps.e7gz.booking.api.LateConfirmWindows;
import ps.e7gz.booking.api.StartTimeAttendance;
import ps.e7gz.booking.internal.VenueAnswerStore.Facts;
import ps.e7gz.common.codes.ActorType;
import ps.e7gz.common.codes.AttendanceResult;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.codes.BookingTimerPauseKind;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.inventory.ledger.api.ActiveReservation;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.inventory.ledger.api.ReservationOwner;
import ps.e7gz.loyalty.api.BookingFeeRefunds;
import ps.e7gz.loyalty.api.BookingRewards;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.operations.api.EmergencyBookings;
import ps.e7gz.operations.api.ResponseTimerPauses;
import ps.e7gz.scheduling.api.ScheduledJobs;

/**
 * {@link EmergencyBookings}: the Booking side of a venue Emergency Closure or a resource Emergency Outage (E7-82; arch 06
 * section 2.5, FR-OPS-08, FR-OPS-12, FR-OPS-44, FR-ATT-10), in the Operations command's transaction under its locks.
 *
 * <p>{@link #end}: the batch is checked first and only then written, so an id that should not be here fails before any
 * write (as the hours-change cancellation, E7-113 D3). Then, in booking-id order and at one instant - the command's
 * {@code now}:
 * <ul>
 *   <li>PENDING: a paused response timer's pause ends ({@code ck_booking_pause_state_scope}), CANCELLED, its reservation
 *       released, T1 withdrawn;</li>
 *   <li>AWAITING: its Late Confirmation closed under the Emergency reason through {@link LateConfirmClosures} - whatever
 *       its deadline: the Emergency reason wins over {@code CUSTOMER_RECONFIRM_TIMEOUT} (FR-OPS-44) - then CANCELLED;</li>
 *   <li>CONFIRMED not yet due: CANCELLED, released, T4 and T5 withdrawn;</li>
 *   <li>CONFIRMED due (T4 late; PO decisions 19-20 Oct 2026, carried forward from E7-59/E7-60, Spec v5 section 31.7):
 *       T4 withdrawn, then T4's own outcome judged at {@code startAt} by {@link StartTimeResolution} - a start writes the
 *       valid CONFIRMED -> ACTIVE dated {@code startAt} ({@link BookingStarts#start}) and the booking is interrupted as
 *       below; a start-time cause writes T4's cancellation dated {@code startAt}
 *       ({@link StartTimeCancellation#cancelWithoutDecision}) and its NOT_APPLICABLE attendance. An undetermined start
 *       (a state since startAt the start table does not decide) is cancelled under the Emergency reason as a booking
 *       that never started: an Emergency is never refused for it (FR-OPS-13), and nothing is guessed about the start;</li>
 *   <li>ACTIVE: CANCELLED under the Emergency reason, its reservation released now - the room is free from the
 *       interruption (FR-AVL-12) - T5 withdrawn, and its attendance resolved from the evidence through
 *       {@link InterruptionAttendance} (FR-ATT-10; E7-69 carry-forward: {@code BOOKING_ATTENDED_INTERRUPTED} through
 *       {@code BookingOutcomes.recordInterruption});</li>
 * </ul>
 * each with its history row (actor VENUE, the declaring account - the Emergency is the venue's explicit operation, as
 * the hours change, E7-113 D1) and {@code BookingCancelled}. Last, every Online Booking's Booking Fee decision in one
 * {@link BookingFeeRefunds#recordDecisions} batch, then the zero-point reconciliation of every
 * {@code BOOKING_ATTENDED_INTERRUPTED} in the same Loyalty context ({@link BookingRewards#reconcileAll}): one Loyalty
 * context per transaction serves every Customer.
 *
 * <p>{@link #pause}: the pause instant on the booking and the jobs {@code PAUSED} (arch 08 section 2.5); the pause
 * history row is Operations'.
 */
@Component
class JdbcEmergencyBookings implements EmergencyBookings {

    private static final Logger log = LoggerFactory.getLogger(JdbcEmergencyBookings.class);

    static final Set<BookingTerminalReason> REASONS = Set.of(BookingTerminalReason.VENUE_EMERGENCY_CLOSURE,
            BookingTerminalReason.RESOURCE_EMERGENCY_OUTAGE);

    private static final Set<BookingStatus> LIVE = EnumSet.of(BookingStatus.PENDING,
            BookingStatus.AWAITING_CUSTOMER_CONFIRMATION, BookingStatus.CONFIRMED, BookingStatus.ACTIVE);

    static final String COLUMNS = """
            SELECT b.id, b.resource_id, b.customer_id, b.source, b.status, b.start_at, b.end_at,
                   (b.response_pause_started_at IS NOT NULL OR b.late_confirm_pause_started_at IS NOT NULL) AS paused
              FROM booking.booking b
            """;

    static final String OVERLAPPING = COLUMNS + """
             WHERE b.venue_id = :venue
               AND (CAST(:resource AS uuid) IS NULL OR b.resource_id = CAST(:resource AS uuid))
               AND b.status IN ('PENDING','AWAITING_CUSTOMER_CONFIRMATION','CONFIRMED','ACTIVE')
               AND tstzrange(b.start_at, b.end_at, '[)') && tstzrange(:from, :to, '[)')
             ORDER BY b.start_at, b.id
            """;

    static final String OUTSIDE = COLUMNS + """
             WHERE b.venue_id = :venue
               AND (CAST(:resource AS uuid) IS NULL OR b.resource_id = CAST(:resource AS uuid))
               AND b.source = 'ONLINE'
               AND b.status IN ('PENDING','AWAITING_CUSTOMER_CONFIRMATION')
               AND NOT (tstzrange(b.start_at, b.end_at, '[)') && tstzrange(:from, :to, '[)'))
             ORDER BY b.start_at, b.id
            """;

    static final String PAUSE_RESPONSE = """
            UPDATE booking.booking SET response_pause_started_at = :at, version = version + 1
             WHERE id = :id AND status = 'PENDING' AND source = 'ONLINE' AND response_pause_started_at IS NULL
            """;

    static final String PAUSE_LATE_CONFIRM = """
            UPDATE booking.booking SET late_confirm_pause_started_at = :at, version = version + 1
             WHERE id = :id AND status = 'AWAITING_CUSTOMER_CONFIRMATION' AND source = 'ONLINE'
               AND late_confirm_pause_started_at IS NULL
            """;

    static final String FEES = "SELECT id, booking_fee_snapshot FROM booking.booking WHERE id IN (:ids)";

    private final VenueAnswerStore store;
    private final ReservationLedger ledger;
    private final BookingFeeRefunds refunds;
    private final BookingRewards rewards;
    private final ScheduledJobs jobs;
    private final Outbox outbox;
    private final ResponseTimerPauses pauses;
    private final LateConfirmClosures closures;
    private final StartTimeResolution resolution;
    private final StartTimeCancellation startCancellation;
    private final BookingStarts starts;
    private final InterruptionAttendance interruptions;
    private final StartTimeAttendance startAttendance;
    private final LateConfirmBookings lateConfirmPauses;
    private final LateConfirmWindows windows;

    JdbcEmergencyBookings(VenueAnswerStore store, ReservationLedger ledger, BookingFeeRefunds refunds,
                          BookingRewards rewards, ScheduledJobs jobs, Outbox outbox, ResponseTimerPauses pauses,
                          LateConfirmClosures closures, StartTimeResolution resolution,
                          StartTimeCancellation startCancellation, BookingStarts starts,
                          InterruptionAttendance interruptions,
                          StartTimeAttendance startAttendance, LateConfirmBookings lateConfirmPauses,
                          LateConfirmWindows windows) {
        this.store = store;
        this.ledger = ledger;
        this.refunds = refunds;
        this.rewards = rewards;
        this.jobs = jobs;
        this.outbox = outbox;
        this.pauses = pauses;
        this.closures = closures;
        this.resolution = resolution;
        this.startCancellation = startCancellation;
        this.starts = starts;
        this.interruptions = interruptions;
        this.startAttendance = startAttendance;
        this.lateConfirmPauses = lateConfirmPauses;
        this.windows = windows;
    }

    @Override
    public List<Live> overlapping(CommandContext command, UUID venueId, UUID resourceId, Instant from, Instant to) {
        return read(command, OVERLAPPING, venueId, resourceId, from, to);
    }

    @Override
    public List<Live> outsideTheWindow(CommandContext command, UUID venueId, UUID resourceId, Instant from,
                                       Instant to) {
        return read(command, OUTSIDE, venueId, resourceId, from, to);
    }

    private static List<Live> read(CommandContext command, String sql, UUID venueId, UUID resourceId, Instant from,
                                   Instant to) {
        Objects.requireNonNull(venueId, "venueId");
        if (!to.isAfter(from)) {
            throw new IllegalArgumentException("[" + from + ", " + to + ") is empty");
        }
        return command.jdbc().sql(sql)
                .param("venue", venueId).param("resource", resourceId).param("from", utc(from)).param("to", utc(to))
                .query(JdbcEmergencyBookings::live).list();
    }

    @Override
    public List<Outcome> end(CommandContext context, UUID venueId, UUID venueAccountId, List<UUID> bookingIds,
                             BookingTerminalReason reason) {
        Objects.requireNonNull(venueId, "venueId");
        Objects.requireNonNull(venueAccountId, "venueAccountId");
        if (!REASONS.contains(Objects.requireNonNull(reason, "reason"))) {
            throw new IllegalArgumentException(reason + " is not an Emergency reason");
        }
        List<UUID> ordered = HoursChangeCancellation.ordered(bookingIds);
        if (ordered.isEmpty()) {
            return List.of();
        }
        Instant now = context.commandNow();
        UUID actor = venueAccountId;
        List<Facts> batch = new ArrayList<>(ordered.size());
        for (UUID bookingId : ordered) {
            Facts booking = store.facts(context, bookingId)
                    .orElseThrow(() -> integrityFailure(bookingId, "no such booking"));
            if (!booking.venueId().equals(venueId)) {
                throw integrityFailure(bookingId, "the booking is not of venue " + venueId);
            }
            if (!LIVE.contains(booking.status())
                    || (booking.status() != BookingStatus.CONFIRMED && booking.status() != BookingStatus.ACTIVE
                    && booking.source() != BookingSource.ONLINE)) {
                throw integrityFailure(bookingId, booking.status() + " " + booking.source()
                        + " booking is not ended by an Emergency");
            }
            if (booking.source() == BookingSource.ONLINE && booking.customerId() == null) {
                throw integrityFailure(bookingId, "Online booking without a Customer");
            }
            batch.add(booking);
        }
        Map<UUID, BigDecimal> fees = fees(context, ordered);

        List<Outcome> outcomes = new ArrayList<>(batch.size());
        List<BookingFeeRefunds.Decision> decisions = new ArrayList<>();
        Map<UUID, UUID> interruptedOutcomes = new HashMap<>();
        for (Facts booking : batch) {
            UUID bookingId = booking.bookingId();
            BookingStatus from = booking.status();
            Ended ended = switch (from) {
                case PENDING -> cancelled(context, booking, reason, now, actor);
                case AWAITING_CUSTOMER_CONFIRMATION -> {
                    closures.closeForCancellation(context, bookingId, reason, now);
                    yield cancelled(context, booking, reason, now, actor);
                }
                case CONFIRMED -> booking.startAt().isAfter(now) ? cancelled(context, booking, reason, now, actor)
                        : lateStart(context, booking, reason, now, actor);
                case ACTIVE -> interrupted(context, booking, reason, now, false, actor);
                default -> throw integrityFailure(bookingId, from + " booking is not ended by an Emergency");
            };
            if (booking.source() == BookingSource.ONLINE) {
                decisions.add(new BookingFeeRefunds.Decision(booking.customerId(), bookingId, ended.history()));
                if (ended.attendance() == AttendanceResult.ATTENDED_INTERRUPTED) {
                    interruptedOutcomes.put(bookingId, booking.customerId());
                }
            }
            outcomes.add(new Outcome(bookingId, booking.resourceId(), booking.source(), from, ended.startedFirst(),
                    ended.reason(), ended.attendance(), booking.source() == BookingSource.ONLINE
                    ? fees.getOrDefault(bookingId, BigDecimal.ZERO) : BigDecimal.ZERO.setScale(2)));
        }
        refunds.recordDecisions(context, decisions);
        rewards.reconcileAll(context, interruptedOutcomes);
        return List.copyOf(outcomes);
    }

    /** A booking that has not started: CANCELLED under the Emergency reason, released, its jobs withdrawn. */
    private Ended cancelled(CommandContext context, Facts booking, BookingTerminalReason reason, Instant now,
                            UUID actor) {
        return cancelled(context, booking, reason, now, ActorType.VENUE, actor);
    }

    private Ended cancelled(CommandContext context, Facts booking, BookingTerminalReason reason, Instant now,
                            ActorType actorType, UUID actor) {
        UUID bookingId = booking.bookingId();
        BookingStatus from = booking.status();
        if (from == BookingStatus.PENDING && booking.responsePaused()) {
            Duration accumulated = store.endResponsePause(context, bookingId, now)
                    .orElseThrow(() -> integrityFailure(bookingId, "the paused response timer did not end"));
            pauses.closeOpen(context, bookingId, now, accumulated);
        }
        if (!store.terminate(context, bookingId, from, BookingStatus.CANCELLED, reason, now, null)) {
            throw integrityFailure(bookingId, "the booking left " + from + " under the command's locks");
        }
        if (from != BookingStatus.AWAITING_CUSTOMER_CONFIRMATION) {
            ledger.release(context, ownReservation(context, booking).id(), now, reason.name());
        }
        UUID history = store.insertTransition(context, bookingId, now, from, BookingStatus.CANCELLED, reason,
                actorType, actor, CorrelationId.require());
        if (from == BookingStatus.PENDING) {
            jobs.supersede(context, VenueAnswers.venueResponseExpiryKey(bookingId));
        } else if (from == BookingStatus.CONFIRMED) {
            jobs.supersede(context, ManualBookingCommand.bookingStartKey(bookingId));
            jobs.supersede(context, ManualBookingCommand.bookingCompleteKey(bookingId));
        }
        publishCancelled(context, booking, reason);
        return new Ended(history, reason, null, false);
    }

    /**
     * A CONFIRMED booking whose start passed with T4 late: T4's own outcome first, as of {@code startAt}, then the
     * Emergency (PO decisions 19-20 Oct 2026; R1 of E7-60 for the same "start first" rule).
     */
    private Ended lateStart(CommandContext context, Facts booking, BookingTerminalReason reason, Instant now,
                            UUID actor) {
        jobs.supersede(context, ManualBookingCommand.bookingStartKey(booking.bookingId()));
        StartTimeResolution.Outcome start = resolution.resolve(context, booking);
        return switch (start) {
            case StartTimeResolution.Outcome.Start started -> {
                starts.start(context, booking);
                Facts active = store.facts(context, booking.bookingId()).orElseThrow();
                yield interrupted(context, active, reason, now, true, actor);
            }
            case StartTimeResolution.Outcome.Cancel cancel -> {
                UUID history = startCancellation.cancelWithoutDecision(context, booking, cancel);
                startAttendance.resolveCancelledAtStart(context, booking.bookingId(), booking.source(),
                        booking.startAt());
                yield new Ended(history, cancel.reason(), null, false);
            }
            case StartTimeResolution.Outcome.Undetermined undetermined -> {
                log.warn("Late start of booking {} undetermined ({}); the Emergency cancels it unstarted "
                                + "(correlationId={})", booking.bookingId(), undetermined.why(),
                        CorrelationId.current().map(UUID::toString).orElse("none"));
                yield cancelled(context, booking, reason, now, actor);
            }
        };
    }

    /** An ACTIVE booking interrupted now: CANCELLED, released now, T5 withdrawn, attendance from the evidence. */
    private Ended interrupted(CommandContext context, Facts booking, BookingTerminalReason reason, Instant now,
                              boolean startedFirst, UUID actor) {
        UUID bookingId = booking.bookingId();
        ActiveReservation reservation = ownReservation(context, booking);
        if (!store.terminate(context, bookingId, BookingStatus.ACTIVE, BookingStatus.CANCELLED, reason, now, null)) {
            throw integrityFailure(bookingId, "the booking left ACTIVE under the command's locks");
        }
        ledger.release(context, reservation.id(), now, reason.name());
        jobs.supersede(context, ManualBookingCommand.bookingCompleteKey(bookingId));
        UUID history = store.insertTransition(context, bookingId, now, BookingStatus.ACTIVE, BookingStatus.CANCELLED,
                reason, ActorType.VENUE, actor, CorrelationId.require());
        AttendanceResult attendance = interruptions.resolveInterrupted(context, new AttendanceBookings.Booking(
                bookingId, booking.venueId(), booking.resourceId(), booking.customerId(), booking.source(),
                BookingStatus.CANCELLED, booking.startAt(), booking.endAt()));
        publishCancelled(context, booking, reason);
        return new Ended(history, reason, attendance, startedFirst);
    }

    @Override
    public List<Live> pause(CommandContext context, UUID venueId, List<UUID> bookingIds) {
        Objects.requireNonNull(venueId, "venueId");
        List<UUID> ordered = HoursChangeCancellation.ordered(bookingIds);
        Instant now = context.commandNow();
        List<Live> paused = new ArrayList<>(ordered.size());
        for (UUID bookingId : ordered) {
            Facts booking = store.facts(context, bookingId)
                    .filter(found -> found.venueId().equals(venueId))
                    .orElseThrow(() -> integrityFailure(bookingId, "no such booking of venue " + venueId));
            String sql = switch (booking.status()) {
                case PENDING -> PAUSE_RESPONSE;
                case AWAITING_CUSTOMER_CONFIRMATION -> PAUSE_LATE_CONFIRM;
                default -> throw integrityFailure(bookingId, booking.status() + " booking has no pausable timer");
            };
            if (context.jdbc().sql(sql).param("id", bookingId).param("at", utc(now)).update() != 1) {
                throw integrityFailure(bookingId, "not an unpaused " + booking.status() + " Online booking");
            }
            if (booking.status() == BookingStatus.PENDING) {
                jobs.pause(context, VenueAnswers.venueResponseExpiryKey(bookingId));
            } else {
                closures.pauseTimers(context, bookingId);
            }
            paused.add(new Live(bookingId, booking.resourceId(), booking.customerId(), booking.source(),
                    booking.status(), booking.startAt(), booking.endAt(), true));
        }
        return List.copyOf(paused);
    }

    // ---------------------------------------------------------------------------------------------- E7-83

    @Override
    public List<PausedTimer> pausedTimers(CommandContext context, UUID venueId, UUID resourceId) {
        Objects.requireNonNull(venueId, "venueId");
        List<PausedRow> rows = context.jdbc().sql(PAUSED + """
                         AND b.venue_id = :venue
                         AND (CAST(:resource AS uuid) IS NULL OR b.resource_id = CAST(:resource AS uuid))
                       ORDER BY b.start_at, b.id""")
                .param("venue", venueId).param("resource", resourceId)
                .query(JdbcEmergencyBookings::pausedRow).list();
        return withDeadlines(context, rows);
    }

    @Override
    public Optional<PausedTimer> pausedTimer(CommandContext context, UUID bookingId) {
        List<PausedRow> rows = context.jdbc().sql(PAUSED + " AND b.id = :id")
                .param("id", Objects.requireNonNull(bookingId, "bookingId"))
                .query(JdbcEmergencyBookings::pausedRow).list();
        return withDeadlines(context, rows).stream().findFirst();
    }

    @Override
    public Duration resume(CommandContext context, UUID bookingId, Instant at, Instant deadline) {
        PausedTimer timer = pausedTimer(context, bookingId)
                .orElseThrow(() -> integrityFailure(bookingId, "no paused timer to resume"));
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(deadline, "deadline");
        Duration accumulated;
        if (timer.kind() == BookingTimerPauseKind.VENUE_RESPONSE) {
            accumulated = store.endResponsePause(context, bookingId, at)
                    .orElseThrow(() -> integrityFailure(bookingId, "the paused response timer did not end"));
            pauses.closeOpen(context, bookingId, at, accumulated);
            if (deadline.isAfter(timer.deadline())) {
                context.jdbc().sql(MOVE_RESPONSE_DEADLINE).param("id", bookingId).param("at", utc(deadline)).update();
            }
            Instant due = deadline.isAfter(timer.deadline()) ? deadline : timer.deadline();
            jobs.rearmIfHeld(context, VenueAnswers.venueResponseExpiryKey(bookingId), due);
        } else {
            accumulated = lateConfirmPauses.endLateConfirmPause(context, bookingId, at)
                    .orElseThrow(() -> integrityFailure(bookingId, "the paused Late Confirm countdown did not end"));
            pauses.closeOpenLateConfirm(context, bookingId, at, accumulated);
            closures.resumeTimers(context, bookingId, at, deadline);
        }
        return accumulated;
    }

    @Override
    public void cancelInterrupted(CommandContext context, UUID bookingId, BookingTerminalReason reason, Instant at) {
        PausedTimer timer = pausedTimer(context, bookingId)
                .orElseThrow(() -> integrityFailure(bookingId, "no paused timer to cancel"));
        Objects.requireNonNull(at, "at");
        boolean fits = timer.kind() == BookingTimerPauseKind.VENUE_RESPONSE
                ? reason == BookingTerminalReason.VENUE_RESPONSE_INTERRUPTED_BY_EMERGENCY
                || reason == BookingTerminalReason.RESOURCE_RESPONSE_INTERRUPTED_BY_EMERGENCY
                : reason == BookingTerminalReason.LATE_CONFIRM_INTERRUPTED_BY_EMERGENCY;
        if (!fits) {
            throw new IllegalArgumentException(reason + " does not end a paused " + timer.kind() + " timer");
        }
        Facts booking = store.facts(context, bookingId).orElseThrow();
        if (timer.kind() == BookingTimerPauseKind.LATE_CONFIRM) {
            // ends the pause, closes its history row, releases the hold, closes the attempt, withdraws T2/T3
            closures.closeForCancellation(context, bookingId, reason, at);
        }
        Ended ended = cancelled(context, booking, reason, at, ActorType.SYSTEM, null);
        refunds.recordDecisions(context, List.of(new BookingFeeRefunds.Decision(booking.customerId(), bookingId,
                ended.history())));
    }

    /** The paused timers' rows; {@code ck_booking_pause_state_scope} ties each pause column to its status. */
    static final String PAUSED = """
            SELECT b.id, b.venue_id, b.resource_id, b.customer_id, b.status, b.start_at, b.venue_response_expires_at,
                   b.response_pause_started_at, EXTRACT(EPOCH FROM b.cumulative_emergency_pause_duration) AS response_sum,
                   b.late_confirm_pause_started_at,
                   EXTRACT(EPOCH FROM b.cumulative_late_confirm_emergency_pause_duration) AS late_confirm_sum
              FROM booking.booking b
             WHERE b.source = 'ONLINE'
               AND ((b.status = 'PENDING' AND b.response_pause_started_at IS NOT NULL)
                    OR (b.status = 'AWAITING_CUSTOMER_CONFIRMATION' AND b.late_confirm_pause_started_at IS NOT NULL))
            """;

    /** FR-OPS-36: the remaining time preserved - the deadline moves later only. */
    static final String MOVE_RESPONSE_DEADLINE = """
            UPDATE booking.booking SET venue_response_expires_at = :at, version = version + 1
             WHERE id = :id AND status = 'PENDING' AND venue_response_expires_at < :at
            """;

    private record PausedRow(UUID bookingId, UUID venueId, UUID resourceId, UUID customerId, BookingStatus status,
                             Instant startAt, Instant responseDeadline, Instant pauseStartedAt, Duration cumulative) {
    }

    private static PausedRow pausedRow(ResultSet rs, int row) throws SQLException {
        BookingStatus status = BookingStatus.valueOf(rs.getString("status"));
        boolean response = status == BookingStatus.PENDING;
        OffsetDateTime started = rs.getObject(response ? "response_pause_started_at" : "late_confirm_pause_started_at",
                OffsetDateTime.class);
        BigDecimal sum =rs.getBigDecimal(response ? "response_sum" : "late_confirm_sum");
        OffsetDateTime responseDeadline = rs.getObject("venue_response_expires_at", OffsetDateTime.class);
        return new PausedRow(rs.getObject("id", UUID.class), rs.getObject("venue_id", UUID.class),
                rs.getObject("resource_id", UUID.class), rs.getObject("customer_id", UUID.class), status,
                rs.getObject("start_at", OffsetDateTime.class).toInstant(),
                responseDeadline == null ? null : responseDeadline.toInstant(), started.toInstant(),
                sum == null ? Duration.ZERO : Duration.ofNanos(sum.movePointRight(9).longValue()));
    }

    /** Each row with its stored deadline: the response deadline, or the open Late Confirmation's through its port. */
    private List<PausedTimer> withDeadlines(CommandContext context, List<PausedRow> rows) {
        List<UUID> awaiting = rows.stream().filter(r -> r.status() == BookingStatus.AWAITING_CUSTOMER_CONFIRMATION)
                .map(PausedRow::bookingId).toList();
        Map<UUID, Instant> lateConfirm = awaiting.isEmpty() ? Map.of() : windows.openDeadlines(context, awaiting);
        List<PausedTimer> timers = new ArrayList<>(rows.size());
        for (PausedRow r : rows) {
            boolean response = r.status() == BookingStatus.PENDING;
            Instant deadline = response ? r.responseDeadline() : lateConfirm.get(r.bookingId());
            if (deadline == null) {
                throw integrityFailure(r.bookingId(), "a paused " + r.status() + " booking has no stored deadline");
            }
            timers.add(new PausedTimer(r.bookingId(), r.venueId(), r.resourceId(), r.customerId(), r.status(),
                    response ? BookingTimerPauseKind.VENUE_RESPONSE : BookingTimerPauseKind.LATE_CONFIRM, r.startAt(),
                    r.pauseStartedAt(), r.cumulative(), deadline));
        }
        return List.copyOf(timers);
    }

    private void publishCancelled(CommandContext context, Facts booking, BookingTerminalReason reason) {
        outbox.publish(context, booking.source() == BookingSource.ONLINE ? BookingEvents.cancelled(booking, reason)
                : BookingEvents.manualCancelled(booking, reason));
    }

    /** The booking's own active BOOKING reservation over exactly its Resource and interval (D11, E7-47). */
    private ActiveReservation ownReservation(CommandContext context, Facts booking) {
        ActiveReservation reservation = ledger.activeOf(context, ReservationOwner.booking(booking.bookingId()))
                .orElseThrow(() -> integrityFailure(booking.bookingId(),
                        booking.status() + " booking holds no active reservation"));
        if (!reservation.resourceId().equals(booking.resourceId())
                || !reservation.plannedStart().equals(booking.startAt())
                || !reservation.plannedEnd().equals(booking.endAt())) {
            throw integrityFailure(booking.bookingId(),
                    "the reservation does not match the booking's Resource and interval");
        }
        return reservation;
    }

    private static Map<UUID, BigDecimal> fees(CommandContext context, List<UUID> bookingIds) {
        Map<UUID, BigDecimal> fees = new HashMap<>();
        context.jdbc().sql(FEES).param("ids", bookingIds).query((rs, n) -> {
            BigDecimal fee = rs.getBigDecimal("booking_fee_snapshot");
            fees.put(rs.getObject("id", UUID.class), fee == null ? BigDecimal.ZERO.setScale(2) : fee);
            return null;
        }).list();
        return fees;
    }

    private static Live live(ResultSet rs, int row) throws SQLException {
        return new Live(rs.getObject("id", UUID.class), rs.getObject("resource_id", UUID.class),
                rs.getObject("customer_id", UUID.class), BookingSource.valueOf(rs.getString("source")),
                BookingStatus.valueOf(rs.getString("status")),
                rs.getObject("start_at", OffsetDateTime.class).toInstant(),
                rs.getObject("end_at", OffsetDateTime.class).toInstant(), rs.getBoolean("paused"));
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static IllegalStateException integrityFailure(UUID bookingId, String detail) {
        log.error("Booking integrity failure: {} (bookingId={}, correlationId={})", detail, bookingId,
                CorrelationId.current().map(UUID::toString).orElse("none"));
        return new IllegalStateException("Booking " + bookingId + " integrity failure: " + detail);
    }

    /** What ending one booking wrote: its terminal history row (the fee decision's basis) and its attendance. */
    private record Ended(UUID history, BookingTerminalReason reason, AttendanceResult attendance,
                         boolean startedFirst) {
    }
}
