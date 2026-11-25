package ps.e7gz.notification.internal;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import ps.e7gz.common.codes.AttendanceResult;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.OutboxEvent;
import ps.e7gz.messaging.api.PermanentDeliveryFailure;

/**
 * Which booking, Late Confirm and attendance notifications an integration event creates (Spec v5 sections 52.2 and
 * 52.5, FR-NTF-09; arch 08 sections 3.2 and 4.4). The ONE place they are decided (PO decision 12 Oct 2026, Sprint 3
 * review): no producer calls {@code Notifications.notify} for them.
 *
 * <p><b>Adding an event</b> (E7-59, E7-60, E7-61, E7-113, E7-114): one entry in {@link #RULES} (or, for a new
 * cancellation reason, in {@link #CANCELLED}), the English push copy of each customer code and the English and Arabic
 * push copy of each venue code in {@code notification/templates_*.properties}, and a test that the event creates each
 * code exactly once ({@code BookingNotificationsIntegrationTest}). The consumer subscribes to every key of
 * {@link #RULES} by itself.
 *
 * <p>Copy rules (arch 08 section 4.5): every code here is a BOOKING code, so the notification carries the Booking ID
 * (FR-NTF-04, refused by the database otherwise); a code saying "cancelled" comes only from {@code BookingCancelled},
 * whose producer wrote the terminal status (FR-NTF-07); {@code VenueAccountEnabled} has no entry (FR-NTF-08).
 */
final class BookingNotificationRules {

    /** Who gets it: the booking's Customer (none for a Manual Booking) or its Venue. */
    enum Recipient {
        CUSTOMER, VENUE
    }

    /** One notification to create: a {@code notification_catalogue} code for one recipient. */
    record Send(Recipient recipient, String code) {
    }

    /** A reminder timer the event starts ({@code ps.e7gz.notification.api.BookingReminders}). */
    enum Reminder {
        /** On {@code BookingCreated}: the venue, 3 minutes before its response deadline. */
        VENUE_PENDING_REQUEST_EXPIRING,
        /** On the Accept's {@code BookingConfirmed}: the customer, 20 minutes before the start. */
        BOOKING_STARTS_SOON
    }

    /** What one event does: the notifications and at most one reminder. */
    record Plan(List<Send> sends, Optional<Reminder> reminder) {

        static final Plan NONE = new Plan(List.of(), Optional.empty());

        Plan {
            sends = List.copyOf(sends);
        }

        boolean isEmpty() {
            return sends.isEmpty() && reminder.isEmpty();
        }
    }

    private static Send customer(String code) {
        return new Send(Recipient.CUSTOMER, code);
    }

    private static Send venue(String code) {
        return new Send(Recipient.VENUE, code);
    }

    private static Plan plan(Send... sends) {
        return new Plan(Arrays.asList(sends), Optional.empty());
    }

    private static Plan plan(Reminder reminder, Send... sends) {
        return new Plan(Arrays.asList(sends), Optional.of(reminder));
    }

    /**
     * {@code BookingCancelled} by its {@code terminalReason}. A reason without an entry sends nothing: its producer task
     * adds it (E7-60 start-time revalidation, E7-80 emergency/owner-review/closure cascades, E7-113 hours change,
     * E7-114 venue cancel).
     *
     * <p>The three operational reasons are produced by an Accept stopped by its cause (FR-BOK-16, E7-47), the start (E7-60)
     * and the resolution of a still-expired request at its start (T6, E7-118):
     * the customer gets the generic {@code BOOKING_AUTO_CANCELLED} (PO decision 13 Oct 2026) and the venue
     * {@code VENUE_BOOKING_AUTO_CANCELLED} (PO decision 12 Oct 2026). E7-80's cascades write the same reasons and want
     * their own customer codes ({@code BOOKING_CANCELLED_VENUE_EMERGENCY}, ...): E7-80 tells them apart by the event's
     * {@code cause} in {@link #operationalCustomerCode}.
     */
    static final Map<BookingTerminalReason, Plan> CANCELLED = Map.ofEntries(
            // E7-49 (D4: the customer notification of the customer's own cancel is E7-55's; PO decision 13 Oct 2026)
            Map.entry(BookingTerminalReason.CUSTOMER_CANCELLED, plan(customer("BOOKING_CANCELLED"))),
            Map.entry(BookingTerminalReason.VENUE_EMERGENCY_CLOSURE,
                    plan(customer("BOOKING_AUTO_CANCELLED"), venue("VENUE_BOOKING_AUTO_CANCELLED"))),
            Map.entry(BookingTerminalReason.RESOURCE_EMERGENCY_OUTAGE,
                    plan(customer("BOOKING_AUTO_CANCELLED"), venue("VENUE_BOOKING_AUTO_CANCELLED"))),
            Map.entry(BookingTerminalReason.RESOURCE_OWNER_REVIEW_REQUIRED,
                    plan(customer("BOOKING_AUTO_CANCELLED"), venue("VENUE_BOOKING_AUTO_CANCELLED"))),
            // The other two start-time reasons (Spec v5 section 31.3: every start-time failure notifies the customer),
            // produced today by a delayed-start no-show (E7-59, Amr Heiba 17 Oct 2026) and later by E7-60's start: the
            // same generic pair as the three operational reasons above
            Map.entry(BookingTerminalReason.VENUE_CLOSURE,
                    plan(customer("BOOKING_AUTO_CANCELLED"), venue("VENUE_BOOKING_AUTO_CANCELLED"))),
            Map.entry(BookingTerminalReason.SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR,
                    plan(customer("BOOKING_AUTO_CANCELLED"), venue("VENUE_BOOKING_AUTO_CANCELLED"))),
            // E7-118 T6 (FR-BOK-23): a request still VENUE_RESPONSE_EXPIRED cancelled at its original start, the customer
            // only - the venue never answered the request. PO decision D2 (20 Oct 2026): ordinary non-response has no
            // code of its own, so the one generic BOOKING_AUTO_CANCELLED. A Venue Account disable does have one, and
            // FR-NTF-06 / Spec v5 section 24.3 forbid presenting it as ordinary non-response (Amr Heiba 17 Nov 2026,
            // subject to PO review): §52.2 BOOKING_CANCELLED_VENUE_ACCOUNT_DISABLED. E7-78's AWAITING -> CANCELLED under
            // the same reason gets the same code. T6's three operational reasons use their entries above.
            Map.entry(BookingTerminalReason.VENUE_DID_NOT_RESPOND_IN_TIME, plan(customer("BOOKING_AUTO_CANCELLED"))),
            Map.entry(BookingTerminalReason.VENUE_ACCOUNT_DISABLED,
                    plan(customer("BOOKING_CANCELLED_VENUE_ACCOUNT_DISABLED"))),
            // E7-48: the venue cancelled its own Manual Booking; there is no customer to tell
            Map.entry(BookingTerminalReason.VENUE_CANCELLED_MANUAL_BOOKING, Plan.NONE),
            // E7-114 (FR-VEN-03): the venue cancelled a CONFIRMED Online Booking with the Owner Password. §52.2 "Booking
            // Cancelled", the existing customer code (Amr Heiba 23 Oct 2026, subject to PO review); the customer only:
            // the venue made the cancellation itself
            Map.entry(BookingTerminalReason.VENUE_CANCELLED_CONFIRMED_BOOKING, plan(customer("BOOKING_CANCELLED"))),
            // E7-113 (O5, FR-VEN-23 step 9, FR-VEN-28): §52.2 "Venue Opening Hours Changed", one per cancelled booking.
            // The customer only: the venue made the change itself. A Manual Booking gets nothing (cancelled(event)).
            Map.entry(BookingTerminalReason.VENUE_OPENING_HOURS_CHANGED,
                    plan(customer("VENUE_OPENING_HOURS_CHANGED"))),
            // E7-71 (O14, FR-TRS-20: the customer is notified): §52.2 "Booking cancelled due to Ban" to the customer, and
            // §52.5 "Booking Auto Cancelled" to the venue - a system cancellation of one of its bookings
            Map.entry(BookingTerminalReason.USER_BANNED,
                    plan(customer("BOOKING_CANCELLED_BAN"), venue("VENUE_BOOKING_AUTO_CANCELLED"))),
            // E7-83 (FR-OPS-38, Spec v5 section 44.3: "the Customer receives an emergency-related notification"): §52.2
            // operational causes, the customer only - the catalogue sends these two codes to the CUSTOMER alone. A
            // response timer's two reasons share one code; no venue non-response blame is told to anyone
            Map.entry(BookingTerminalReason.VENUE_RESPONSE_INTERRUPTED_BY_EMERGENCY,
                    plan(customer("BOOKING_RESPONSE_INTERRUPTED_BY_EMERGENCY"))),
            Map.entry(BookingTerminalReason.RESOURCE_RESPONSE_INTERRUPTED_BY_EMERGENCY,
                    plan(customer("BOOKING_RESPONSE_INTERRUPTED_BY_EMERGENCY"))),
            Map.entry(BookingTerminalReason.LATE_CONFIRM_INTERRUPTED_BY_EMERGENCY,
                    plan(customer("LATE_CONFIRM_INTERRUPTED_BY_EMERGENCY"))));

    /** One entry per integration event this module turns into booking notifications. */
    static final Map<String, Function<OutboxEvent, Plan>> RULES = Map.ofEntries(
            // E7-46: a customer's request. Both §52.2 "Booking Created" (PO decision 13 Oct 2026) and §52.5 "New
            // Booking Request" (decision A6: Web Push while the Dashboard is closed)
            Map.entry(IntegrationEvents.BOOKING_CREATED, event -> plan(Reminder.VENUE_PENDING_REQUEST_EXPIRING,
                    customer("BOOKING_CREATED"), venue("VENUE_NEW_BOOKING_REQUEST"))),
            // E7-47 Accept; a Manual Booking created CONFIRMED (E7-48) has no customer and gets nothing
            Map.entry(IntegrationEvents.BOOKING_CONFIRMED, event -> manual(event) ? Plan.NONE
                    : plan(Reminder.BOOKING_STARTS_SOON, customer("BOOKING_CONFIRMED"))),
            // E7-47 Reject (FR-BOK-17) and Reject After Expiry (FR-BOK-21): two codes
            Map.entry(IntegrationEvents.BOOKING_REJECTED, event -> switch (reason(event)) {
                case VENUE_REJECTED -> plan(customer("BOOKING_REJECTED"));
                case REJECTED_AFTER_EXPIRY -> plan(customer("REJECTED_AFTER_EXPIRY"));
                default -> throw new PermanentDeliveryFailure("BookingRejected " + event.id()
                        + " has an unexpected terminalReason " + reason(event));
            }),
            // E7-47 T1 (FR-BOK-18): ordinary expiry, its own code (FR-NTF-06). E7-78 (PO decision E1, 19 Nov 2026): the
            // same transition caused by a Venue Account disable gets no notification at all - this code says the venue
            // did not respond in time, which FR-NTF-06 and FR-VEN-11 forbid there; the app shows the cause from the
            // Booking Event VENUE_RESPONSE_INTERRUPTED_BY_ACCOUNT_DISABLE
            Map.entry(IntegrationEvents.VENUE_RESPONSE_EXPIRED, event ->
                    event.payload().path(IntegrationEvents.INTERRUPTED_BY_ACCOUNT_DISABLE).asBoolean(false)
                            ? Plan.NONE : plan(customer("VENUE_RESPONSE_EXPIRED"))),
            // E7-78 (FR-ATT-25, PO decision E2 19 Nov 2026): a disable overlaps the customer's ACTIVE booking, which
            // continues - §52.2 operational causes, In-App + Push, never cancellation wording (FR-NTF-07)
            Map.entry(IntegrationEvents.ACTIVE_SESSION_ATTENDANCE_RECORDING_AFFECTED,
                    event -> plan(customer("ACTIVE_SESSION_ATTENDANCE_RECORDING_AFFECTED"))),
            Map.entry(IntegrationEvents.BOOKING_CANCELLED, BookingNotificationRules::cancelled),
            // No notification (PO decision 13 Oct 2026): a walk-in has no customer; at CONFIRMED -> ACTIVE (E7-60) the
            // BOOKING_STARTS_SOON reminder finds the booking no longer CONFIRMED and supersedes itself
            Map.entry(IntegrationEvents.BOOKING_STARTED, event -> Plan.NONE),
            // E7-58 Late Confirm (O3; D2: its five canonical events, no BookingConfirmed or BookingCancelled). §52.2
            // "Late Confirmation Received" (FR-LCF-06)
            Map.entry(IntegrationEvents.LATE_CONFIRM_INITIATED, event -> plan(customer("LATE_CONFIRMATION_RECEIVED"))),
            // T3, about a minute before the deadline, only for a window over 60 s (FR-LCF-12)
            Map.entry(IntegrationEvents.LATE_CONFIRM_WARNING_DUE,
                    event -> plan(customer("LATE_CONFIRMATION_EXPIRING"))),
            // §52.5 "Customer Accepted Late Confirmation". No BOOKING_STARTS_SOON reminder: a late-confirmed booking
            // gets none (PO decision 13 Oct 2026)
            Map.entry(IntegrationEvents.LATE_CONFIRM_ACCEPTED, event -> plan(venue("VENUE_LATE_CONFIRM_ACCEPTED"))),
            // §52.5 "Customer Declined Late Confirmation"
            Map.entry(IntegrationEvents.LATE_CONFIRM_DECLINED, event -> plan(venue("VENUE_LATE_CONFIRM_DECLINED"))),
            // T2: §52.2 and §52.5 "Customer Confirmation Expired", one for each side
            Map.entry(IntegrationEvents.LATE_CONFIRM_TIMED_OUT, event -> plan(customer("CUSTOMER_CONFIRMATION_EXPIRED"),
                    venue("VENUE_CUSTOMER_CONFIRMATION_EXPIRED"))),
            // E7-83 (FR-OPS-41, Spec v5 section 44.6; PCR-008 leaves it to E7-83): an Emergency paused the customer's
            // Late Confirm countdown - §52.2 "Late Confirmation Paused". A paused venue-response timer tells no one
            Map.entry(IntegrationEvents.TIMER_PAUSED, event -> "LATE_CONFIRM".equals(event.text("pauseKind"))
                    ? plan(customer("LATE_CONFIRMATION_PAUSED")) : Plan.NONE),
            // E7-59 attendance (O13). §52.2 "Check-in Recorded" and "No-show Recorded", the customer only: the venue
            // recorded them itself. Mark Present - Late has no §52.2 notification of its own; its customer-facing
            // outcome is "Attendance-Unconfirmed Completion" at completion (E7-61)
            Map.entry(IntegrationEvents.CHECK_IN_RECORDED, event -> plan(customer("CHECK_IN_RECORDED"))),
            Map.entry(IntegrationEvents.LATE_PRESENT_RECORDED, event -> Plan.NONE),
            Map.entry(IntegrationEvents.NO_SHOW_RECORDED, event -> plan(customer("NO_SHOW_RECORDED"))),
            // E7-61 completion (O12). BookingCompleted has no §52.2 code of its own; an attended completion's points
            // notification (POINTS_EARNED) is E7-66's, Sprint 4
            Map.entry(IntegrationEvents.BOOKING_COMPLETED, event -> Plan.NONE),
            // §52.2 "Attendance-Unconfirmed Completion" (FR-NTF-05: it says no attendance reward was applied), the
            // customer only, from the result the completion resolved (attendanceResolved below)
            Map.entry(IntegrationEvents.ATTENDANCE_RESOLVED, BookingNotificationRules::attendanceResolved),
            // E7-70 Admin attendance changes. §52.2 "No-show Reversed" and "Check-in Reversed / Attendance Corrected",
            // the customer only (§52.2: the customer is told whenever an Admin materially changes a No-show or an
            // Attendance Result). One per Admin command: a reversal with its further correction is one event, one code
            Map.entry(IntegrationEvents.ATTENDANCE_CORRECTED, BookingNotificationRules::corrected));
    // No rule for a conflicting Late Confirm Accept: it is the synchronous 409 LATE_CONFIRM_CUSTOMER_CONFLICT and no
    // event, so LATE_CONFIRM_SCHEDULE_CONFLICT is never produced (F1, PCR-008)

    private BookingNotificationRules() {
    }

    static Set<String> eventTypes() {
        return RULES.keySet();
    }

    static Plan planFor(OutboxEvent event) {
        Function<OutboxEvent, Plan> rule = RULES.get(event.eventType());
        if (rule == null) {
            throw new PermanentDeliveryFailure("No booking notification rule for " + event.eventType());
        }
        return rule.apply(event);
    }

    /**
     * {@code BookingCancelled}: the {@link #CANCELLED} plan of its reason. An hours change cancelling a Manual Booking
     * notifies nobody (E7-113): there is no customer, and the venue made the change itself.
     */
    private static Plan cancelled(OutboxEvent event) {
        BookingTerminalReason reason = reason(event);
        if (reason == BookingTerminalReason.VENUE_OPENING_HOURS_CHANGED && manual(event)) {
            return Plan.NONE;
        }
        String customerCode = operationalCustomerCode(reason, event.payload().path(IntegrationEvents.CANCEL_CAUSE)
                .asText(""));
        if (customerCode != null) {
            return plan(customer(customerCode), venue("VENUE_BOOKING_AUTO_CANCELLED"));
        }
        return CANCELLED.getOrDefault(reason, Plan.NONE);
    }

    /**
     * E7-80 (PO decisions 25 Nov 2026, Q2, Q3, R3; Spec v5 section 52.2 operational causes, FR-NTF-06): the customer
     * code of an operational cancellation by its reason and {@code cause}, or {@code null} for the {@link #CANCELLED}
     * entry. An emergency or owner-review cascade and the start time each have their own code; an Accept stopped by its
     * cause, T6 and an event without a (known) cause keep the generic one (PO decision 13 Oct 2026). A Venue Closure has
     * no code of its own (Q3); an integrity error always has its own.
     */
    static String operationalCustomerCode(BookingTerminalReason reason, String cause) {
        boolean cascade = IntegrationEvents.CAUSE_CASCADE.equals(cause);
        boolean startTime = IntegrationEvents.CAUSE_START_TIME.equals(cause);
        return switch (reason) {
            case SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR -> "BOOKING_CANCELLED_SYSTEM_INTEGRITY_ERROR";
            case VENUE_EMERGENCY_CLOSURE -> cascade ? "BOOKING_CANCELLED_VENUE_EMERGENCY"
                    : startTime ? "BOOKING_START_CANCELLED_EMERGENCY" : null;
            case RESOURCE_EMERGENCY_OUTAGE -> cascade ? "BOOKING_CANCELLED_RESOURCE_EMERGENCY"
                    : startTime ? "BOOKING_START_CANCELLED_EMERGENCY" : null;
            case RESOURCE_OWNER_REVIEW_REQUIRED -> cascade || startTime ? "BOOKING_CANCELLED_OWNER_REVIEW" : null;
            default -> null;
        };
    }

    /**
     * {@code AttendanceResolved} (E7-61) by the result and source its one producer, T5, writes: an Online Booking's
     * ATTENDANCE_UNCONFIRMED tells the customer; its ATTENDED (the points notification is E7-66's) and a Manual
     * Booking's NOT_APPLICABLE send nothing. Any other combination - a result a completion never resolves, a Manual
     * Booking with Product attendance, an unknown {@code source} - is a producer defect, never silently dropped. A
     * result resolved by a later producer (the ACTIVE interruptions) is added here by its own task.
     */
    private static Plan attendanceResolved(OutboxEvent event) {
        String result = event.text("attendanceResult");
        JsonNode source = event.payload().path("source");
        boolean manual = !source.isMissingNode() && !source.isNull();
        if (manual && !"MANUAL".equals(source.asText(null))) {
            throw new PermanentDeliveryFailure(event.eventType() + " " + event.id() + " has an unknown source "
                    + source);
        }
        if (manual && result.equals(AttendanceResult.NOT_APPLICABLE.name())) {
            return Plan.NONE;
        }
        if (!manual && result.equals(AttendanceResult.ATTENDED.name())) {
            return Plan.NONE;
        }
        if (!manual && result.equals(AttendanceResult.ATTENDANCE_UNCONFIRMED.name())) {
            return plan(customer("ATTENDANCE_UNCONFIRMED_COMPLETION"));
        }
        throw new PermanentDeliveryFailure(event.eventType() + " " + event.id() + " has an unexpected attendanceResult "
                + result + " for " + (manual ? "a Manual" : "an Online") + " Booking");
    }

    /** {@code AttendanceCorrected.correction} names the customer code; anything else is a producer defect. */
    private static Plan corrected(OutboxEvent event) {
        String correction = event.text("correction");
        return switch (correction == null ? "" : correction) {
            case "NO_SHOW_REVERSED" -> plan(customer("NO_SHOW_REVERSED"));
            // §52.2 "Check-in Reversed / Attendance Corrected" is one customer notification; a reversed Mark Present -
            // Late is the same material change of the customer's attendance (FR-NTF-11)
            case "ATTENDANCE_CORRECTED", "CHECK_IN_REVERSED", "LATE_PRESENT_REVERSED" ->
                    plan(customer("ATTENDANCE_CORRECTED"));
            default -> throw new PermanentDeliveryFailure(event.eventType() + " " + event.id()
                    + " has an unknown correction " + correction);
        };
    }

    /** A Manual Booking's events carry {@code source: MANUAL} and no customer (E7-48, D6). */
    static boolean manual(OutboxEvent event) {
        return "MANUAL".equals(event.payload().path("source").asText(null));
    }

    private static BookingTerminalReason reason(OutboxEvent event) {
        String reason = event.text("terminalReason");
        try {
            return BookingTerminalReason.valueOf(reason);
        } catch (IllegalArgumentException e) {
            throw new PermanentDeliveryFailure(event.eventType() + " " + event.id() + " has an unknown terminalReason "
                    + reason);
        }
    }
}
