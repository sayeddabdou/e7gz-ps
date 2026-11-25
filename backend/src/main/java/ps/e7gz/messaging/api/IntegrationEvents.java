package ps.e7gz.messaging.api;

import java.util.Set;

/**
 * The names of integration events ({@code technical.outbox_event.event_type}, arch 08 section 3.2) that a producer and
 * a consumer in different modules must agree on. Architecture-internal, never shown to anyone (arch 08 section 3.1).
 * A producer task adds its names here when it starts writing the event.
 */
public final class IntegrationEvents {

    /** {@code {areaId}}, aggregate {@code AREA}: an area was deactivated (arch 03 section 4; PO decision 3 Oct 2026). */
    public static final String AREA_DEACTIVATED = "AreaDeactivated";

    /** {@code {venueId, previousStatus, newStatus}}, aggregate {@code VENUE} (arch 08 section 3.2). */
    public static final String VENUE_MARKETPLACE_STATUS_CHANGED = "VenueMarketplaceStatusChanged";

    /**
     * {@code {challengeId, sealedCode}}, aggregate {@code OTP_CHALLENGE}: a phone OTP to send by SMS (FR-NTF-01, E7-41).
     * The code is encrypted (AES-GCM) in the payload and never in plain text (arch 10 section 2.2; PO decision 9 Oct
     * 2026).
     */
    public static final String PHONE_OTP_ISSUED = "PhoneOtpIssued";

    /**
     * {@code {notificationId}}, aggregate {@code NOTIFICATION}: a notification has PUSH or EMAIL deliveries to send
     * (E7-43). Written by {@code Notifications.notify} in the command that creates the notification, and again by the
     * admin "try again" of a dead-lettered delivery; the notification worker sends every delivery still due.
     */
    public static final String NOTIFICATION_DELIVERY_REQUESTED = "NotificationDeliveryRequested";

    /**
     * {@code {bookingId, customerId, venueId, resourceId, startAt, endAt}}, aggregate {@code BOOKING}: an Online
     * Booking was created PENDING (arch 08 section 3.2, arch 01 section 11.1; E7-46, PO decision 9 Oct 2026).
     */
    public static final String BOOKING_CREATED = "BookingCreated";

    /**
     * {@code {venueAccountId, venueId}}, aggregate {@code VENUE_ACCOUNT}: the Platform Admin disabled the venue's
     * account (arch 08 sections 3.2 and 5, FR-VEN-08): the venue live channel closes its open streams at once (E7-44,
     * PO decision 10 Oct 2026).
     */
    public static final String VENUE_ACCOUNT_DISABLED = "VenueAccountDisabled";

    /**
     * {@code {reservationId, resourceId, venueId, period: {start, end}}}, aggregate {@code RESERVATION}: a ledger
     * row was inserted active (arch 08 section 3.2; E7-46, PO decision 9 Oct 2026).
     */
    public static final String RESERVATION_HELD = "ReservationHeld";

    /**
     * {@code {reservationId, resourceId, venueId, period: {start, end}}}, aggregate {@code RESERVATION}: an active ledger
     * row was released (arch 08 section 3.2; E7-47, the payload of {@link #RESERVATION_HELD}).
     */
    public static final String RESERVATION_RELEASED = "ReservationReleased";

    /**
     * {@code {bookingId, customerId, venueId, resourceId, startAt, endAt}}, aggregate {@code BOOKING}: a booking is now
     * CONFIRMED - the venue accepted a PENDING Online Booking (arch 06 section 2.1, arch 08 section 3.2; E7-47, PO
     * decision D4), or a future Manual Booking was created CONFIRMED, with {@code customerId} null and
     * {@code source: MANUAL} (PO decision 10 Oct 2026, E7-48, D6).
     */
    public static final String BOOKING_CONFIRMED = "BookingConfirmed";

    /**
     * {@code {bookingId, customerId, venueId, resourceId, startAt, endAt, source}}, aggregate {@code BOOKING}: a booking
     * is now ACTIVE (arch 08 section 3.2). Written since E7-48 for a walk-in Manual Booking created ACTIVE at the command
     * instant, with {@code customerId} null and {@code source: MANUAL} (PO decision 10 Oct 2026, E7-48, D6); the
     * CONFIRMED -> ACTIVE start is E7-60's.
     */
    public static final String BOOKING_STARTED = "BookingStarted";

    /**
     * {@code {bookingId, customerId, venueId, resourceId, startAt, endAt, terminalReason}}, aggregate {@code BOOKING}:
     * the venue rejected the booking - {@code VENUE_REJECTED} from PENDING or {@code REJECTED_AFTER_EXPIRY} from
     * VENUE_RESPONSE_EXPIRED (FR-BOK-17, FR-BOK-21; E7-47, PO decision D4). The venue's reason text is not in it.
     */
    public static final String BOOKING_REJECTED = "BookingRejected";

    /**
     * {@code {bookingId, customerId, venueId, resourceId, startAt, endAt, venueResponseExpiresAt}}, aggregate
     * {@code BOOKING}: T1 moved a PENDING booking to the non-terminal VENUE_RESPONSE_EXPIRED (FR-BOK-18; E7-47, PO
     * decision D4). A Venue Account disable writes it too, before the deadline, with
     * {@code interruptedByAccountDisable: true} ({@link #INTERRUPTED_BY_ACCOUNT_DISABLE}; FR-VEN-09, E7-78): no
     * customer notification for that one (PO decision E1, 19 Nov 2026).
     */
    public static final String VENUE_RESPONSE_EXPIRED = "VenueResponseExpired";

    /** The {@link #VENUE_RESPONSE_EXPIRED} payload flag of an expiry caused by a Venue Account disable (E7-78). */
    public static final String INTERRUPTED_BY_ACCOUNT_DISABLE = "interruptedByAccountDisable";

    /**
     * {@code {bookingId, customerId, venueId, resourceId, startAt, endAt, restrictionIntervalId, disabledAt}}, aggregate
     * {@code BOOKING}: a Venue Account disable overlaps the ACTIVE Online Booking, which continues (FR-ATT-14,
     * FR-ATT-25; E7-78, PO decision E2 19 Nov 2026). One per booking per disable; the customer is told
     * {@code ACTIVE_SESSION_ATTENDANCE_RECORDING_AFFECTED}, never that it was cancelled (FR-NTF-07).
     */
    public static final String ACTIVE_SESSION_ATTENDANCE_RECORDING_AFFECTED =
            "ActiveSessionAttendanceRecordingAffected";

    /**
     * {@code {bookingId, customerId, venueId, resourceId, startAt, endAt, terminalReason}}, aggregate {@code BOOKING}: a
     * booking was CANCELLED under {@code terminalReason} - from E7-47 the operational cause that stopped a venue's
     * Accept (FR-BOK-16, PO decision D4); from E7-48 the venue's cancellation of a CONFIRMED or ACTIVE Manual Booking,
     * VENUE_CANCELLED_MANUAL_BOOKING, with {@code customerId} null and {@code source: MANUAL} (PCR-007; PO decision 10
     * Oct 2026, E7-48, D6).
     */
    public static final String BOOKING_CANCELLED = "BookingCancelled";

    /**
     * Optional {@code BookingCancelled} member: what cancelled the booking, where its {@code terminalReason} alone does
     * not say it (E7-80, PO decision 25 Nov 2026 Q2: one customer code per cause). Absent on every other cancellation
     * and on events written before E7-80: the consumer treats absent as the generic case.
     */
    public static final String CANCEL_CAUSE = "cause";
    /** An emergency or owner-review cascade ended the booking (E7-82, E7-84). */
    public static final String CAUSE_CASCADE = "CASCADE";
    /** The start-time resolution cancelled it (E7-60 T4; an emergency's overdue start, E7-82). */
    public static final String CAUSE_START_TIME = "START_TIME";
    /** A venue's Accept stopped by its operational cause (FR-BOK-16, E7-47). */
    public static final String CAUSE_ACCEPT = "ACCEPT";
    /** A still-expired request resolved at its original start (T6, FR-BOK-23, E7-118). */
    public static final String CAUSE_EXPIRED_REQUEST = "EXPIRED_REQUEST";

    /**
     * {@code {bookingId, customerId, venueId, venueName, resourceId, startAt, endAt, lateConfirmExpiresAt}},
     * aggregate {@code BOOKING}: the venue sent a Late Confirmation - VENUE_RESPONSE_EXPIRED ->
     * AWAITING_CUSTOMER_CONFIRMATION with its hold, until {@code lateConfirmExpiresAt} = MIN(initiation + 5 min,
     * startAt) (arch 06 section 2.2, arch 08 section 3.2; FR-LCF-06, FR-LCF-09; E7-58, PO decision D2 12 Oct 2026).
     * {@code venueName} is the venue's display name at the event (E7-58 Q4, 13 Oct 2026), for the notification copy.
     */
    public static final String LATE_CONFIRM_INITIATED = "LateConfirmInitiated";

    /**
     * {@code {bookingId, customerId, venueId, venueName, resourceId, startAt, endAt}}, aggregate {@code BOOKING}:
     * the customer accepted - AWAITING_CUSTOMER_CONFIRMATION -> CONFIRMED, same Booking ID, price and fee (FR-LCF-08,
     * FR-LCF-13; E7-58, D2, Q4). O3 writes no {@code BookingConfirmed}.
     */
    public static final String LATE_CONFIRM_ACCEPTED = "LateConfirmAccepted";

    /**
     * {@code {bookingId, customerId, venueId, venueName, resourceId, startAt, endAt, terminalReason}}, aggregate
     * {@code BOOKING}: the customer declined - CANCELLED with {@code terminalReason} CUSTOMER_DECLINED_LATE_CONFIRM
     * (FR-LCF-13; E7-58, D2, Q4). O3 writes no {@code BookingCancelled}.
     */
    public static final String LATE_CONFIRM_DECLINED = "LateConfirmDeclined";

    /**
     * {@code {bookingId, customerId, venueId, venueName, resourceId, startAt, endAt, lateConfirmExpiresAt,
     * terminalReason}}, aggregate {@code BOOKING}: T2 cancelled the booking as of its stored deadline, CANCELLED with
     * {@code terminalReason} CUSTOMER_RECONFIRM_TIMEOUT (FR-LCF-13; E7-58, D2, Q4). O3 writes no
     * {@code BookingCancelled}.
     */
    public static final String LATE_CONFIRM_TIMED_OUT = "LateConfirmTimedOut";

    /**
     * {@code {bookingId, customerId, venueId, venueName, resourceId, startAt, endAt, lateConfirmExpiresAt}}, aggregate
     * {@code BOOKING}: T3, the Late Confirmation expires in about a minute - written once per attempt and only for a
     * window longer than 60 seconds (FR-LCF-12; E7-58, D2, Q4). For the customer only (no venue live signal).
     */
    public static final String LATE_CONFIRM_WARNING_DUE = "LateConfirmWarningDue";

    /**
     * {@code {bookingId, customerId, venueId, resourceId, startAt, endAt, recordedAt}}, aggregate {@code BOOKING}: the
     * venue checked the customer in at {@code recordedAt}, inside {@code [startAt, startAt + 15 min)} (arch 07 section
     * 7.1, arch 08 section 3.2; FR-ATT-01, FR-ATT-02; E7-59, PO decision 12 Oct 2026). The booking stays ACTIVE.
     */
    public static final String CHECK_IN_RECORDED = "CheckInRecorded";

    /**
     * {@code {bookingId, customerId, venueId, resourceId, startAt, endAt, recordedAt}}, aggregate {@code BOOKING}: the
     * venue marked the customer Present - Late inside {@code [startAt + 15 min, endAt)} (FR-ATT-03; E7-59). The booking
     * stays ACTIVE; no points, no Verified Player progress, no counter reset.
     */
    public static final String LATE_PRESENT_RECORDED = "LatePresentRecorded";

    /**
     * {@code {bookingId, customerId, venueId, resourceId, startAt, endAt, recordedAt}}, aggregate {@code BOOKING}: the
     * venue recorded a no-show inside {@code [startAt + 15 min, endAt)} - ACTIVE -> NO_SHOW, Attendance Result
     * ABSENT_NO_SHOW, the reservation released at {@code recordedAt} (FR-ATT-04, FR-ATT-05; E7-59). The one event of
     * this fact: there is no {@code BookingNoShowRecorded} (PO decision 12 Oct 2026).
     */
    public static final String NO_SHOW_RECORDED = "NoShowRecorded";

    /**
     * {@code {bookingId, customerId, venueId, resourceId, startAt, endAt}}, aggregate {@code BOOKING}: T5 completed the
     * booking - ACTIVE -> COMPLETED as of its stored {@code endAt}, the reservation released (arch 08 section 3.2, arch 07
     * section 7.3; E7-61, PO decision 12 Oct 2026). A Manual Booking's carries {@code customerId} null and
     * {@code source: MANUAL} (as E7-48's D6 events). Written in the completion transaction with
     * {@link #ATTENDANCE_RESOLVED}.
     */
    public static final String BOOKING_COMPLETED = "BookingCompleted";

    /**
     * {@code {bookingId, customerId, venueId, resourceId, startAt, endAt, attendanceResult}}, aggregate {@code BOOKING}:
     * the booking's Attendance Result was resolved by its default at completion (FR-ATT-07, FR-ATT-08; E7-61) - ATTENDED
     * or ATTENDANCE_UNCONFIRMED for an Online Booking, NOT_APPLICABLE for a Manual Booking, whose event carries
     * {@code customerId} null and {@code source: MANUAL}.
     */
    public static final String ATTENDANCE_RESOLVED = "AttendanceResolved";

    /**
     * {@code {bookingId, customerId, venueId, resourceId, startAt, endAt, correction, previousResult, attendanceResult,
     * bookingStatus, correctedAt}}, aggregate {@code BOOKING}: a Platform Admin changed the booking's attendance
     * (arch 03 section 13, arch 08 section 3.2; FR-ATT-11..13; E7-70). {@code correction} is {@code NO_SHOW_REVERSED}
     * - the no-show was reversed, the booking moved NO_SHOW -> {@code bookingStatus} (CANCELLED before the original
     * endAt, COMPLETED at or after it) and resolved {@code attendanceResult} - or {@code ATTENDANCE_CORRECTED}, a resolved
     * result replaced with the status unchanged. One event per Admin command, written in its transaction; there is no
     * separate {@code BookingCancelled} or {@code BookingCompleted} for a reversal (one fact, one event).
     */
    public static final String ATTENDANCE_CORRECTED = "AttendanceCorrected";

    /**
     * {@code {userId, transitionId, fromStatus, toStatus, cause}}, aggregate {@code USER}: one operational Verified Player
     * transition (arch 08 section 3.2; Spec v5 sections 8.4-8.9, FR-ACC-12; E7-72) - automatic
     * ({@code TRUST_QUALIFICATION}, {@code TRUST_CORRECTION}) or a Platform Admin's ({@code ADMIN}). One event per
     * {@code transitionId}, written in the transition's transaction; the customer's notification follows from it.
     */
    public static final String VERIFIED_PLAYER_TRANSITIONED = "VerifiedPlayerTransitioned";

    /**
     * {@code {userId, banInstanceId, penaltyStage, activatedAt, endsAt, cancelledBookings}}, aggregate
     * {@code BAN_INSTANCE}: a replay threshold crossing activated a Ban (FR-TRS-14..18, arch 07 section 5; E7-71), in
     * the command that crossed it, with its booking cascade already executed (each cancelled booking also has its own
     * {@code BookingCancelled} / USER_BANNED). One per Ban Instance.
     */
    public static final String BAN_ACTIVATED = "BanActivated";

    /**
     * {@code {userId, banInstanceId, previousPenaltyStage, penaltyStage, previousEndsAt, endsAt}}, aggregate
     * {@code BAN_INSTANCE}: a further qualification while banned advanced the stage and extended the same instance to
     * {@code MAX(previousEndsAt, now + stage duration)} (FR-TRS-22; E7-71) - {@code endsAt} may equal
     * {@code previousEndsAt}. One per consumed qualification; the cascade is never repeated.
     */
    public static final String BAN_EXTENDED = "BanExtended";

    /**
     * {@code {userId, banInstanceId, penaltyStage, endsAt}}, aggregate {@code BAN_INSTANCE}: the Ban reached its
     * {@code endsAt} and is EXPIRED (FR-TRS-23; T10, arch 08 section 1; E7-71). Previously cancelled bookings do not
     * return.
     */
    public static final String BAN_EXPIRED = "BanExpired";

    /**
     * {@code {userId, banInstanceId, penaltyStage, endsAt, correctionEventId}}, aggregate {@code BAN_INSTANCE}: an
     * attendance correction left the ACTIVE Ban without replay-valid support, so it is CORRECTED now (FR-TRS-24,
     * FR-TRS-25; E7-71). Cancelled bookings and sold slots do not return.
     */
    public static final String BAN_CORRECTED = "BanCorrected";

    /**
     * {@code {userId, banInstanceId, penaltyStage, previousEndsAt, endsAt}}, aggregate {@code BAN_INSTANCE}: a Platform
     * Admin lifted the ACTIVE Ban now (FR-TRS-23, FR-TRS-27; E7-71). Cancelled bookings do not return.
     */
    public static final String BAN_LIFTED = "BanLifted";

    /**
     * {@code {userId, banInstanceId, penaltyStage, previousEndsAt, endsAt, correctionEventId?}}, aggregate
     * {@code BAN_INSTANCE}: the ACTIVE Ban's end was brought forward - by a Platform Admin, or by the Admin attendance
     * correction that left it replay support only until {@code endsAt} (then {@code correctionEventId}; FR-TRS-25,
     * FR-TRS-27; E7-71). It stays ACTIVE until then.
     */
    public static final String BAN_SHORTENED = "BanShortened";

    /**
     * {@code {userId, trustEventId, counterAfter, penaltyStageAfter}}, aggregate {@code USER}: a Platform Admin set the
     * customer's no-show counter and penalty stage (ADMIN_TRUST_ADJUSTMENT, FR-TRS-27; E7-71).
     */
    public static final String TRUST_ADJUSTED = "TrustAdjusted";

    /**
     * {@code {venueId, resourceId}}, aggregate {@code RESOURCE}: the venue disabled a room (FR-VEN-03; E7-26, published
     * since E7-56, PO decision 12 Oct 2026). Its four siblings below carry the same payload.
     */
    public static final String RESOURCE_DISABLED = "ResourceDisabled";
    /** The venue re-enabled a disabled room (FR-VEN-03); as {@link #RESOURCE_DISABLED}. */
    public static final String RESOURCE_RE_ENABLED = "ResourceReEnabled";
    /** The venue archived a room (FR-VEN-03); as {@link #RESOURCE_DISABLED}. */
    public static final String RESOURCE_ARCHIVED = "ResourceArchived";
    /** The venue restored an archived room to ACTIVE (FR-VEN-03); as {@link #RESOURCE_DISABLED}. */
    public static final String RESOURCE_RESTORED = "ResourceRestored";
    /** PS4 to PS5 or back (FR-VEN-14, FR-VEN-15; its price overrides were cleared); as {@link #RESOURCE_DISABLED}. */
    public static final String RESOURCE_CONSOLE_CHANGED = "ResourceConsoleChanged";
    /**
     * {@code {venueId}}, aggregate {@code VENUE}: the venue's default prices changed (FR-VEN-43; E7-27, published since
     * E7-56).
     */
    public static final String VENUE_PRICING_CHANGED = "VenuePricingChanged";
    /**
     * {@code {venueId, resourceId}}, aggregate {@code RESOURCE}: a room's price overrides were set or reset
     * (FR-VEN-43; E7-27, published since E7-56).
     */
    public static final String ROOM_OVERRIDE_CHANGED = "RoomOverrideChanged";

    /**
     * Aggregate {@code LOYALTY}, the points transaction: one points ledger row was posted (written by the database
     * producer {@code loyalty.post_points}, never by Java; ADR-026). Payload {@code {transactionId, cause, bookingId,
     * referralId, requestedAmount, appliedAmount, unrecoveredAmount, balanceBefore, balanceAfter, recipientUserId,
     * occurredAt, ...}}. Consumed since E7-66 for the customer's points notifications (PO decision 23 Oct 2026).
     */
    public static final String LOYALTY_POINTS_LEDGER_COMMITTED = "LOYALTY_POINTS_LEDGER_COMMITTED";

    /**
     * Written by the loyalty producer functions ({@code loyalty.write_outbox}) for every Wallet ledger row: a Booking Fee
     * paid from the Wallet ({@code {walletTransactionId, receiptId, bookingId, amount < 0, ...}}, PCR-009) or a refund
     * credited to it ({@code {walletTransactionId, refundId, receiptId, bookingId, amount > 0, ...}}), plus
     * {@code recipientUserId} and {@code occurredAt}. Consumed since E7-80 for the refund confirmation.
     */
    public static final String LOYALTY_WALLET_LEDGER_COMMITTED = "LOYALTY_WALLET_LEDGER_COMMITTED";

    /**
     * {@code {venueId, resourceId, maintenanceId}}, aggregate {@code MAINTENANCE}: the venue created a Maintenance window
     * on one of its rooms (FR-OPS-01, arch 08 section 3.2; E7-81, Sprint 2 review, PO decision 12 Oct 2026). Its two
     * siblings below carry the same payload.
     */
    public static final String MAINTENANCE_CREATED = "MaintenanceCreated";
    /** The venue moved, extended, shortened or re-worded a Maintenance window (FR-OPS-04); as {@link #MAINTENANCE_CREATED}. */
    public static final String MAINTENANCE_EDITED = "MaintenanceEdited";
    /** The venue deleted a Maintenance window (FR-OPS-01); as {@link #MAINTENANCE_CREATED}. */
    public static final String MAINTENANCE_DELETED = "MaintenanceDeleted";
    /**
     * {@code {venueId, closureId}}, aggregate {@code VENUE_CLOSURE}: the venue saved a new normal Closure (FR-VEN-29,
     * arch 08 section 3.2; E7-81). An edit or a delete has no event of its own in arch 08: it releases and holds the
     * Closure's per-room {@code CLOSURE} rows, whose {@link #RESERVATION_RELEASED} / {@link #RESERVATION_HELD} refresh
     * the venue the same way.
     */
    public static final String CLOSURE_CREATED = "ClosureCreated";

    /**
     * {@code {venueId, emergencyId, scope, resourceId?}}, aggregate {@code EMERGENCY}: a venue Emergency Closure or a
     * resource Emergency Outage was declared (FR-OPS-06, FR-OPS-11; arch 08 section 3.2; E7-82, Sprint 2 review, PO
     * decision 12 Oct 2026), in the command that declares it. Its three siblings below carry the same payload.
     */
    public static final String EMERGENCY_DECLARED = "EmergencyDeclared";
    /**
     * The expected end passed with the Emergency still active: ACTIVE -> AWAITING_CONFIRMATION (FR-OPS-18; E7-82). As
     * {@link #EMERGENCY_DECLARED} plus {@code ownerReviewEntered}: whether that same step entered an Owner Review
     * (FR-OPS-26). E7-80 (FR-OPS-25, PO decision 25 Nov 2026 R2): the venue's one emergency reminder, unless the review
     * was entered - then its owner-review notice says it.
     */
    public static final String EMERGENCY_AWAITING_CONFIRMATION = "EmergencyAwaitingConfirmation";
    /** The expected end moved later (FR-OPS-22); as {@link #EMERGENCY_DECLARED}. */
    public static final String EMERGENCY_EXTENDED = "EmergencyExtended";
    /** END EMERGENCY: the selling block ended (FR-OPS-19); as {@link #EMERGENCY_DECLARED}. */
    public static final String EMERGENCY_ENDED = "EmergencyEnded";
    /** Converted into its target in one commit (FR-OPS-23, FR-OPS-24); as {@link #EMERGENCY_DECLARED}. */
    public static final String EMERGENCY_CONVERTED = "EmergencyConverted";

    /**
     * {@code {bookingId, venueId, resourceId, customerId, pauseKind, pauseCause, pausedAt}}, aggregate {@code BOOKING}: an
     * Emergency paused a booking's venue-response timer ({@code pauseKind} VENUE_RESPONSE) or Late Confirm countdown
     * (LATE_CONFIRM) - FR-OPS-34, FR-OPS-35, arch 08 sections 2.5 and 3.2; E7-83. The Late Confirm one tells the customer
     * (FR-OPS-41: §52.2 LATE_CONFIRMATION_PAUSED).
     */
    public static final String TIMER_PAUSED = "TimerPaused";
    /**
     * {@code {bookingId, venueId, resourceId, customerId, pauseKind, resumedAt, deadline, remainingSeconds}}: the paused
     * timer resumed with its remaining time (FR-OPS-36, FR-OPS-39; E7-83).
     */
    public static final String TIMER_RESUMED = "TimerResumed";
    /**
     * {@code {bookingId, venueId, resourceId, customerId, pauseKind, terminalReason, capReachedAt}}: the 30-minute
     * cumulative pause cap was reached and the booking cancelled under {@code terminalReason} (FR-OPS-37, FR-OPS-38;
     * E7-83). The cancellation's own {@code BookingCancelled} is written in the same command and tells the customer.
     */
    public static final String TIMER_PAUSE_CAP_REACHED = "TimerPauseCapReached";

    /**
     * {@code {venueId, resourceId, restriction, ownerReviewId?}}, aggregate {@code RESOURCE}: the Resource's operational
     * restriction changed - it entered OWNER_REVIEW_REQUIRED at an Emergency's expected or actual end (FR-OPS-26,
     * FR-OPS-27; the venue owner is told, FR-OPS-29) or its Owner Review was resolved (FR-OPS-33). E7-84.
     */
    public static final String RESOURCE_RESTRICTION_CHANGED = "ResourceRestrictionChanged";

    /**
     * Every event that can change a venue's availability and so refreshes its Nearest Availability rows (arch 05
     * section 5.3, arch 08 section 7): reservation hold or release, Resource state, selling blocks (maintenance,
     * closures, emergencies), opening hours and prices. Each carries the {@code venueId} it touches;
     * {@link #AREA_DEACTIVATED} carries an {@code areaId} instead. Written so far (PO decision 9 Oct 2026): the E7-23
     * events, {@link #RESERVATION_HELD} (E7-46), {@link #RESERVATION_RELEASED} (E7-47) and the room state and price
     * events (E7-56, PO decision 12 Oct 2026), and the Maintenance and Closure events (E7-81); the other producers add
     * theirs in their own tasks.
     */
    public static final Set<String> INVENTORY_AFFECTING = Set.of(
            RESERVATION_HELD, RESERVATION_RELEASED,
            RESOURCE_DISABLED, RESOURCE_RE_ENABLED, RESOURCE_ARCHIVED, RESOURCE_RESTORED,
            RESOURCE_CONSOLE_CHANGED, RESOURCE_RESTRICTION_CHANGED,
            MAINTENANCE_CREATED, MAINTENANCE_EDITED, MAINTENANCE_DELETED,
            CLOSURE_CREATED,
            EMERGENCY_DECLARED, EMERGENCY_EXTENDED, EMERGENCY_ENDED, EMERGENCY_CONVERTED,
            "OpeningHoursChanged",
            VENUE_PRICING_CHANGED, ROOM_OVERRIDE_CHANGED,
            VENUE_MARKETPLACE_STATUS_CHANGED,
            AREA_DEACTIVATED);

    private IntegrationEvents() {
    }
}
