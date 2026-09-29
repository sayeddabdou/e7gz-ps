package ps.e7gz.common.codes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import ps.e7gz.common.codes.CheckConstraintParser.ArrayUse;

/**
 * The reviewable contract between the Java enums of {@code ps.e7gz.common.codes} and the closed {@code CHECK} lists of
 * the physical model (arch 04 section 1 P5, ADR-016, ADR-027). The database spelling is the authority.
 *
 * <ul>
 *   <li>{@link #CANONICAL}: every column whose complete closed list defines a Java enum. An enum shared by several
 *       columns is mapped to every one of them, and each list must equal the enum exactly. A few domains have no
 *       ARRAY and are written as {@code column = 'literal'} branches ({@link Mode#LITERAL_BRANCHES}).</li>
 *   <li>{@link #SUBSETS}: lists inside conditional/shape constraints (e.g. "these statuses need a terminal_at"). They
 *       are NOT separate enums; each must only use members of the named enum.</li>
 *   <li>{@link #NON_TEXT}: numeric lists, which are not closed value sets.</li>
 *   <li>{@link #DERIVED_ONLY}: enums that are computed by the application and have no database column.</li>
 * </ul>
 * Every {@code ARRAY[...]} in every CHECK constraint must be claimed by exactly one of the first three lists.
 */
final class ClosedValueContract {

    private ClosedValueContract() {
    }

    static final String EQ_ANY = "= ANY";
    static final String CONTAINED_BY = "<@";

    /** How the canonical list of a column is taken from its constraint. */
    enum Mode {
        /** Exactly one list for the column. */
        SINGLE,
        /** The domain is split across branches: union of all lists, which must not overlap. */
        UNION,
        /** One list is the full domain and the others are subsets of it (e.g. "numeric types only"). */
        SUPERSET,
        /**
         * No ARRAY: the domain is the set of {@code column = 'literal'} branches of the constraint (e.g.
         * {@code (state = 'IN_PROGRESS' AND ...) OR (state = 'COMPLETED' AND ...)}). {@code occurrences} is the number
         * of branches; the column must not also be compared with an ARRAY or with any operator other than {@code =}.
         */
        LITERAL_BRANCHES
    }

    /** Operator of a {@link Mode#LITERAL_BRANCHES} mapping. */
    static final String EQ_LITERAL = "=";

    /**
     * @param table       {@code schema.table}
     * @param occurrences how many lists for {@code column}/{@code operator} the constraint must contain
     */
    record Canonical(Class<? extends Enum<?>> type, String table, String constraint, String column, String operator,
                     Mode mode, int occurrences) {

        String key() {
            return table + "." + constraint;
        }

        @Override
        public String toString() {
            return type.getSimpleName() + " <- " + key() + " (" + column + ")";
        }
    }

    record Subset(String table, String constraint, String column, Class<? extends Enum<?>> type) {

        String key() {
            return table + "." + constraint;
        }
    }

    record NonText(String table, String constraint, String column) {

        String key() {
            return table + "." + constraint;
        }
    }

    /** Enums the application derives; they have no single persisted closed-list column (not DB-backed). */
    static final Set<Class<? extends Enum<?>>> DERIVED_ONLY = Set.of(
            ScheduleDisplayState.class); // Dashboard schedule cell, computed from bookings/holds/maintenance/closures

    static final List<Canonical> CANONICAL = List.of(
            one(Activity.class, "booking.booking", "ck_booking_activity_values", "activity"),
            one(Activity.class, "inventory.venue_activity_availability", "ck_venue_activity_availability_activity", "activity"),
            one(Activity.class, "pricing.venue_default_price", "ck_default_price_activity", "activity"),
            one(Activity.class, "resource.resource", "ck_resource_activity", "activity"),
            one(Activity.class, "tournament.tournament", "ck_tournament_activity", "activity"),
            one(Activity.class, "venue.venue_activity", "ck_venue_activity", "activity"),

            one(ActorType.class, "audit.audit_event", "ck_audit_actor_type", "actor_type"),
            one(ActorType.class, "booking.booking_history", "ck_booking_history_actor_type_values", "actor_type"),
            one(ActorType.class, "resource.resource_state_change", "ck_resource_state_change_actor_type", "actor_type"),
            one(ActorType.class, "tournament.tournament_history", "ck_tournament_history_actor_type", "actor_type"),
            one(ActorType.class, "technical.idempotency_record", "ck_idempotency_values", "principal_type"),

            one(AttendanceResult.class, "attendance.attendance_record", "ck_attendance_result_values", "result"),
            one(AuthSubjectType.class, "iam.auth_refresh_token", "ck_refresh_token_subject_type", "subject_type"),
            one(BanConsequenceType.class, "trust.ban_qualification_consumption", "ck_ban_qualification_consumption_type",
                    "consequence_type"),
            one(BanInstanceStatus.class, "trust.ban_instance", "ck_ban_instance_status", "status"),
            one(BookingEventDetailCode.class, "booking.booking_history", "ck_booking_history_detail_code_scope", "detail_code"),
            one(BookingEventType.class, "booking.booking_history", "ck_booking_history_event_type_values", "event_type"),
            one(BookingHistoryEntryKind.class, "booking.booking_history", "ck_booking_history_entry_kind", "entry_kind"),
            one(BookingSource.class, "booking.booking", "ck_booking_source_values", "source"),

            one(BookingStatus.class, "booking.booking", "ck_booking_status_values", "status"),
            one(BookingStatus.class, "booking.booking_history", "ck_booking_history_status_values", "from_status"),
            one(BookingStatus.class, "booking.booking_history", "ck_booking_history_status_values", "to_status"),

            one(BookingTerminalReason.class, "booking.booking", "ck_booking_terminal_reason_values", "terminal_reason"),
            one(BookingTerminalReason.class, "booking.booking_history", "ck_booking_history_terminal_reason_values",
                    "terminal_reason"),
            one(BookingTerminalReason.class, "loyalty.booking_fee_refund", "ck_booking_fee_refund_terminal_reason",
                    "terminal_reason"),

            one(BookingTimerPauseKind.class, "operations.booking_timer_pause_history", "ck_booking_timer_pause_kind",
                    "pause_kind"),
            one(CascadeType.class, "trust.cascade_execution", "ck_cascade_execution_type", "cascade_type"),
            new Canonical(ConfigurationValueType.class, "config.configuration_key", "ck_configuration_key_values",
                    "value_type", EQ_ANY, Mode.SUPERSET, 2),

            one(ConsoleType.class, "booking.booking", "ck_booking_snapshot_dimensions", "console_type"),
            one(ConsoleType.class, "pricing.resource_price_override", "ck_override_console", "console_type"),
            one(ConsoleType.class, "pricing.venue_default_price", "ck_default_price_console", "console_type"),
            one(ConsoleType.class, "resource.resource", "ck_resource_console_type", "console_type"),
            one(ConsoleType.class, "resource.resource_state_change", "ck_state_change_console_values", "from_console"),
            one(ConsoleType.class, "resource.resource_state_change", "ck_state_change_console_values", "to_console"),
            one(ConsoleType.class, "tournament.tournament_resource", "ck_tournament_resource_console_snapshot",
                    "console_type_snapshot"),

            one(DevicePlatform.class, "notification.device_token", "ck_device_token_platform", "platform"),
            new Canonical(EmergencyConversionTarget.class, "operations.emergency", "ck_emergency_conversion_target",
                    "converted_to", EQ_ANY, Mode.UNION, 2),
            one(EmergencyScope.class, "operations.emergency", "ck_emergency_scope", "scope"),
            one(EmergencyStatus.class, "operations.emergency", "ck_emergency_status", "status"),
            one(FeeTreatment.class, "booking.booking", "ck_booking_fee_treatment_values", "fee_treatment"),
            one(FeeTreatment.class, "loyalty.booking_fee_refund", "ck_booking_fee_refund_treatment", "treatment"),
            one(LateConfirmCustomerResponse.class, "booking.late_confirmation", "ck_late_confirmation_customer_response",
                    "customer_response"),
            one(MarketplaceStatus.class, "venue.venue", "ck_venue_marketplace_status", "marketplace_status"),

            one(NotificationChannel.class, "notification.notification_delivery", "ck_notification_delivery_channel",
                    "channel"),
            new Canonical(NotificationChannel.class, "notification.notification_catalogue",
                    "ck_notification_catalogue_values", "allowed_channels", CONTAINED_BY, Mode.SINGLE, 1),
            one(NotificationDeliveryStatus.class, "notification.notification_delivery", "ck_notification_delivery_status",
                    "status"),
            one(NotificationRecipientType.class, "notification.notification", "ck_notification_recipient_type",
                    "recipient_type"),
            new Canonical(NotificationRecipientType.class, "notification.notification_catalogue",
                    "ck_notification_catalogue_values", "recipient_types", CONTAINED_BY, Mode.SINGLE, 1),
            one(NotificationReferenceType.class, "notification.notification_catalogue", "ck_notification_catalogue_values",
                    "reference_type"),

            one(OpeningDayMode.class, "venue.venue_opening_day", "ck_opening_day_mode", "mode"),
            one(OperationalInterruptionSubjectType.class, "operations.operational_interruption_record",
                    "ck_operational_interruption_subject_type", "subject_type"),
            one(OperationalRestriction.class, "resource.resource", "ck_resource_restriction", "operational_restriction"),
            one(OperationalRestriction.class, "resource.resource_state_change", "ck_state_change_restriction_values",
                    "from_restriction"),
            one(OperationalRestriction.class, "resource.resource_state_change", "ck_state_change_restriction_values",
                    "to_restriction"),
            branches(IdempotencyState.class, "technical.idempotency_record", "ck_idempotency_state", "state", 2),
            one(OtpPurpose.class, "iam.otp_challenge", "ck_otp_purpose", "purpose"),
            one(OutboxState.class, "technical.outbox_event", "ck_outbox_state", "state"),
            one(OwnerPasswordOutcome.class, "audit.audit_event", "ck_audit_owner_password_outcome", "owner_password_outcome"),
            one(OwnerReviewResolution.class, "operations.owner_review", "ck_owner_review_resolution", "resolution"),

            one(PlayMode.class, "booking.booking", "ck_booking_snapshot_dimensions", "play_mode"),
            one(PlayMode.class, "pricing.resource_price_override", "ck_override_play_mode", "play_mode"),
            one(PlayMode.class, "pricing.venue_default_price", "ck_default_price_play_mode", "play_mode"),
            one(PlayMode.class, "tournament.tournament", "ck_tournament_play_mode", "play_mode"),

            one(PointsTransactionCause.class, "loyalty.points_transaction", "ck_points_transaction_cause", "cause"),
            one(RateLimiterType.class, "iam.security_rate_limit_counter", "ck_rate_limit_limiter_type", "limiter_type"),
            one(ReferralInvalidationKind.class, "loyalty.referral", "ck_referral_shape", "invalidation_kind"),
            one(ReferralStatus.class, "loyalty.referral", "ck_referral_status", "status"),
            one(ReservationType.class, "inventory.resource_reservation", "ck_reservation_type", "reservation_type"),

            one(ResourceBaseState.class, "resource.resource", "ck_resource_base_state", "base_state"),
            one(ResourceBaseState.class, "resource.resource_state_change", "ck_state_change_base_values", "from_base_state"),
            one(ResourceBaseState.class, "resource.resource_state_change", "ck_state_change_base_values", "to_base_state"),

            one(RestrictionSource.class, "iam.venue_account_restriction_interval", "ck_restriction_source", "source"),
            branches(RewardSourceKind.class, "loyalty.reward_application", "ck_reward_application_source", "source_kind", 2),
            one(ReviewModerationAction.class,"review.review_moderation_event", "ck_review_moderation_action", "action"),
            one(ReviewStatus.class, "review.review", "ck_review_status", "status"),
            one(ReviewStatus.class, "review.review_moderation_event", "ck_review_moderation_status_values",
                    "previous_status"),
            one(ReviewStatus.class, "review.review_moderation_event", "ck_review_moderation_status_values", "new_status"),
            one(ScheduledJobStatus.class, "technical.scheduled_job", "ck_scheduled_job_status", "status"),

            one(TournamentHostType.class, "tournament.tournament", "ck_tournament_host_type", "host_type"),
            one(TournamentParticipantIneligibleReason.class, "tournament.tournament_participant",
                    "ck_tournament_participant_ineligible_reason", "ineligible_reason"),
            one(TournamentParticipantStatus.class, "tournament.tournament_participant", "ck_tournament_participant_status",
                    "status"),
            one(TournamentResourceRemovalCause.class, "tournament.tournament_resource",
                    "ck_tournament_resource_removal_shape", "removal_cause"),
            one(TournamentStatus.class, "tournament.tournament", "ck_tournament_status_values", "status"),
            one(TournamentStatus.class, "tournament.tournament_history", "ck_tournament_history_status_values",
                    "from_status"),
            one(TournamentStatus.class, "tournament.tournament_history", "ck_tournament_history_status_values", "to_status"),
            one(TournamentTerminalReason.class, "tournament.tournament", "ck_tournament_terminal_reason_values",
                    "terminal_reason"),
            one(TournamentTerminalReason.class, "tournament.tournament_history",
                    "ck_tournament_history_terminal_reason_values", "terminal_reason"),
            one(TournamentVisibility.class, "tournament.tournament", "ck_tournament_visibility", "visibility"),

            one(TrustEventType.class, "trust.trust_event", "ck_trust_event_type", "event_type"),
            one(VenueAccountLanguage.class, "iam.venue_account", "ck_venue_account_language", "language_preference"),
            one(VenueAccountStatus.class, "iam.venue_account", "ck_venue_account_status", "account_status"),
            one(VenueClosureKind.class, "operations.venue_closure", "ck_venue_closure_kind", "kind"),
            one(VerifiedPlayerQualification.class, "trust.trust_projection", "ck_trust_projection_vp_qualification",
                    "vp_qualification"),
            one(VerifiedPlayerStatus.class, "trust.trust_projection", "ck_trust_projection_vp_operational_status",
                    "vp_operational_status"),
            one(VerifiedPlayerStatus.class, "trust.verified_player_transition", "ck_verified_player_transition_from_status",
                    "from_status"),
            one(VerifiedPlayerStatus.class, "trust.verified_player_transition", "ck_verified_player_transition_to_status",
                    "to_status"),
            one(VerifiedPlayerTransitionCause.class, "trust.verified_player_transition",
                    "ck_verified_player_transition_cause", "cause"));

    /** Business subset/shape lists: every member must belong to the enum, but none defines a new type. */
    static final List<Subset> SUBSETS = List.of(
            new Subset("audit.audit_event", "ck_audit_owner_password_coherence", "owner_password_outcome",
                    OwnerPasswordOutcome.class),
            new Subset("booking.booking", "ck_booking_commitment_period_shape", "status", BookingStatus.class),
            new Subset("booking.booking", "ck_booking_fee_treatment_scope", "status", BookingStatus.class),
            new Subset("booking.booking", "ck_booking_manual_states", "status", BookingStatus.class),
            new Subset("booking.booking", "ck_booking_snapshot_dimensions", "activity", Activity.class),
            new Subset("booking.booking", "ck_booking_terminal_at", "status", BookingStatus.class),
            new Subset("booking.booking", "ck_booking_terminal_reason", "status", BookingStatus.class),
            new Subset("booking.booking_history", "ck_booking_history_transition_terminal_reason", "to_status",
                    BookingStatus.class),
            new Subset("booking.late_confirmation", "ck_late_confirmation_response_shape", "customer_response",
                    LateConfirmCustomerResponse.class),
            new Subset("inventory.resource_reservation", "ck_reservation_owner_shape", "reservation_type",
                    ReservationType.class),
            new Subset("notification.notification_catalogue", "ck_notification_catalogue_values", "allowed_channels",
                    NotificationChannel.class), // allowed_channels = ARRAY['SMS'] for OTP-only entries
            new Subset("notification.notification_delivery", "ck_notification_delivery_shape", "status",
                    NotificationDeliveryStatus.class),
            new Subset("operations.emergency", "ck_emergency_terminal_shape", "status", EmergencyStatus.class),
            new Subset("pricing.venue_default_price", "ck_price_dimensions", "activity", Activity.class),
            new Subset("pricing.venue_default_price", "ck_price_dimensions", "console_type", ConsoleType.class),
            new Subset("pricing.venue_default_price", "ck_price_dimensions", "play_mode", PlayMode.class),
            new Subset("resource.resource", "ck_console_iff_playstation", "activity", Activity.class),
            new Subset("resource.resource", "ck_console_iff_playstation", "console_type", ConsoleType.class),
            new Subset("review.review_moderation_event", "ck_review_moderation_transition", "previous_status",
                    ReviewStatus.class),
            new Subset("technical.scheduled_job", "ck_scheduled_job_lifecycle_shape", "status", ScheduledJobStatus.class),
            new Subset("tournament.tournament", "ck_tournament_play_mode", "activity", Activity.class),
            new Subset("tournament.tournament", "ck_tournament_terminal_reason", "status", TournamentStatus.class),
            new Subset("tournament.tournament_history", "ck_tournament_history_transition_shape", "to_status",
                    TournamentStatus.class),
            new Subset("trust.ban_cascade_booking_target", "ck_ban_cascade_booking_target_status", "from_status",
                    BookingStatus.class), // the only statuses a ban cascade targets
            new Subset("trust.cascade_execution", "ck_cascade_execution_source_shape", "cascade_type", CascadeType.class),
            new Subset("trust.trust_event", "ck_trust_booking_outcome_booking_ref", "event_type", TrustEventType.class),
            new Subset("trust.trust_event", "ck_trust_correction_booking_ref", "event_type", TrustEventType.class),
            new Subset("trust.trust_event", "ck_trust_event_corrected_outcome", "event_type", TrustEventType.class),
            new Subset("trust.trust_event", "ck_trust_event_corrected_outcome", "corrected_outcome",
                    AttendanceResult.class), // a correction never sets NOT_APPLICABLE
            new Subset("venue.venue_opening_day", "ck_opening_day_custom_times", "mode", OpeningDayMode.class));

    /** Numeric lists: not closed value sets, no enum. */
    static final List<NonText> NON_TEXT = List.of(
            new NonText("trust.trust_projection", "ck_trust_projection_stage_threshold", "penalty_stage"),
            new NonText("trust.trust_projection", "ck_trust_projection_threshold", "current_threshold"));

    private static Canonical one(Class<? extends Enum<?>> type, String table, String constraint, String column) {
        return new Canonical(type, table, constraint, column, EQ_ANY, Mode.SINGLE, 1);
    }

    private static Canonical branches(Class<? extends Enum<?>> type, String table, String constraint, String column,
                                      int branches) {
        return new Canonical(type, table, constraint, column, EQ_LITERAL, Mode.LITERAL_BRANCHES, branches);
    }

    /** A mapping that cannot be checked: missing constraint, unexpected shape, empty or duplicated list. */
    static final class ContractViolation extends RuntimeException {
        ContractViolation(String message) {
            super(message);
        }
    }

    /** The canonical list for {@code mapping}, from constraint definitions keyed {@code schema.table.constraint}. */
    static List<String> extract(Canonical mapping, Map<String, String> definitions) {
        String definition = definitions.get(mapping.key());
        if (definition == null) {
            throw new ContractViolation(mapping + ": constraint " + mapping.key() + " does not exist in the database");
        }
        return extract(mapping, definition);
    }

    static List<String> extract(Canonical mapping, String definition) {
        if (mapping.mode() == Mode.LITERAL_BRANCHES) {
            return extractLiteralBranches(mapping, definition);
        }
        List<ArrayUse> uses = CheckConstraintParser.parse(definition).stream()
                .filter(u -> mapping.column().equals(u.column()) && mapping.operator().equals(u.operator()))
                .toList();
        if (uses.size() != mapping.occurrences()) {
            throw new ContractViolation(mapping + ": expected " + mapping.occurrences() + " '" + mapping.column() + " "
                    + mapping.operator() + " ARRAY[...]' list(s), found " + uses.size() + " in " + definition);
        }
        for (ArrayUse use : uses) {
            if (use.numeric()) {
                throw new ContractViolation(mapping + ": list is numeric, not a closed value set");
            }
            if (use.values().isEmpty()) {
                throw new ContractViolation(mapping + ": list has no members");
            }
            if (new HashSet<>(use.values()).size() != use.values().size()) {
                throw new ContractViolation(mapping + ": list has duplicate members " + use.values());
            }
        }
        return switch (mapping.mode()) {
            case SINGLE -> uses.get(0).values();
            case UNION -> {
                List<String> all = uses.stream().flatMap(u -> u.values().stream()).toList();
                if (new HashSet<>(all).size() != all.size()) {
                    throw new ContractViolation(mapping + ": branches overlap " + all);
                }
                yield all;
            }
            case SUPERSET -> {
                List<ArrayUse> full = uses.stream()
                        .filter(candidate -> uses.stream().allMatch(u -> candidate.values().containsAll(u.values())))
                        .toList();
                if (full.size() != 1) {
                    throw new ContractViolation(mapping + ": expected exactly one list containing all the others, found "
                            + full.size());
                }
                yield full.get(0).values();
            }
            case LITERAL_BRANCHES -> throw new IllegalStateException("handled by extractLiteralBranches");
        };
    }

    private static List<String> extractLiteralBranches(Canonical mapping, String definition) {
        if (CheckConstraintParser.parse(definition).stream().anyMatch(u -> mapping.column().equals(u.column()))) {
            throw new ContractViolation(mapping + ": the column also has an ARRAY list; map it as an ARRAY domain");
        }
        List<CheckConstraintParser.LiteralUse> uses = CheckConstraintParser.literalComparisons(definition).stream()
                .filter(u -> mapping.column().equals(u.column()))
                .toList();
        List<String> otherOperators = uses.stream().filter(u -> !EQ_LITERAL.equals(u.operator()))
                .map(u -> u.column() + " " + u.operator() + " '" + u.value() + "'").toList();
        if (!otherOperators.isEmpty()) {
            throw new ContractViolation(mapping + ": branches do not close the domain, found " + otherOperators);
        }
        List<String> values = uses.stream().map(CheckConstraintParser.LiteralUse::value).toList();
        if (values.isEmpty()) {
            throw new ContractViolation(mapping + ": list has no members (no '" + mapping.column() + " = literal' branch)");
        }
        if (new HashSet<>(values).size() != values.size()) {
            throw new ContractViolation(mapping + ": list has duplicate members " + values);
        }
        if (values.size() != mapping.occurrences()) {
            throw new ContractViolation(mapping + ": expected " + mapping.occurrences() + " '" + mapping.column()
                    + " = literal' branches, found " + values.size() + " " + values + " in " + definition);
        }
        return values;
    }

    /** Difference between an enum and a database list, in both directions. */
    record Parity(Set<String> missingInJava, Set<String> extraInJava) {

        boolean matches() {
            return missingInJava.isEmpty() && extraInJava.isEmpty();
        }
    }

    static Parity compare(Class<? extends Enum<?>> type, List<String> databaseValues) {
        Set<String> java = names(type);
        Set<String> db = new LinkedHashSet<>(databaseValues);
        Set<String> missing = new TreeSet<>(db);
        missing.removeAll(java);
        Set<String> extra = new TreeSet<>(java);
        extra.removeAll(db);
        return new Parity(missing, extra);
    }

    static Set<String> names(Class<? extends Enum<?>> type) {
        return Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Every {@code ARRAY[...]} in every constraint must be canonical, a declared subset, or declared numeric; subset
     * lists must stay inside their enum; and no declaration may point at a list that no longer exists.
     */
    static List<String> coverageViolations(Map<String, String> definitions) {
        List<String> violations = new ArrayList<>();
        Set<String> seenSubsets = new HashSet<>();
        Set<String> seenNonText = new HashSet<>();
        definitions.forEach((key, definition) -> {
            for (ArrayUse use : CheckConstraintParser.parse(definition)) {
                String where = key + " (" + use.column() + " " + use.operator() + " " + use.values() + ")";
                if (use.column() == null || use.operator() == null) {
                    violations.add("Cannot tell which column this list belongs to: " + where);
                    continue;
                }
                boolean canonical = CANONICAL.stream().anyMatch(c -> c.key().equals(key)
                        && c.column().equals(use.column()) && c.operator().equals(use.operator()));
                if (canonical) {
                    continue;
                }
                Subset subset = SUBSETS.stream()
                        .filter(s -> s.key().equals(key) && s.column().equals(use.column())).findFirst().orElse(null);
                NonText nonText = NON_TEXT.stream()
                        .filter(n -> n.key().equals(key) && n.column().equals(use.column())).findFirst().orElse(null);
                if (subset != null) {
                    seenSubsets.add(subset.key() + "/" + subset.column());
                    Set<String> outside = new TreeSet<>(use.values());
                    outside.removeAll(names(subset.type()));
                    if (use.numeric() || use.values().isEmpty() || !outside.isEmpty()) {
                        violations.add("Subset list is not inside " + subset.type().getSimpleName() + " " + outside
                                + ": " + where);
                    }
                } else if (nonText != null) {
                    seenNonText.add(nonText.key() + "/" + nonText.column());
                    if (!use.numeric()) {
                        violations.add("Declared numeric but is a text list: " + where);
                    }
                } else {
                    violations.add("Unclassified closed list - map it to an enum, or declare it a subset: " + where);
                }
            }
        });
        SUBSETS.stream().filter(s -> !seenSubsets.contains(s.key() + "/" + s.column()))
                .forEach(s -> violations.add("Stale SUBSETS entry, no such list in the database: " + s));
        NON_TEXT.stream().filter(n -> !seenNonText.contains(n.key() + "/" + n.column()))
                .forEach(n -> violations.add("Stale NON_TEXT entry, no such list in the database: " + n));
        return violations;
    }
}
