package ps.e7gz.trust.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.trust.internal.VerifiedPlayerFlows.OWNER;
import static ps.e7gz.trust.internal.VerifiedPlayerFlows.cascadeKeys;
import static ps.e7gz.trust.internal.VerifiedPlayerFlows.projection;
import static ps.e7gz.trust.internal.VerifiedPlayerFlows.revokedAlerts;
import static ps.e7gz.trust.internal.VerifiedPlayerFlows.transitionedEvents;
import static ps.e7gz.trust.internal.VerifiedPlayerFlows.transitions;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import ps.e7gz.booking.internal.BookingFlows;
import ps.e7gz.booking.internal.BookingFlows.Booking;
import ps.e7gz.common.codes.AttendanceResult;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.fixtures.Fixtures;
import ps.e7gz.trust.api.TrustReconciler;
import ps.e7gz.trust.internal.VerifiedPlayerFlows.Transition;

/**
 * E7-72 - Verified Player (arch 07 section 6, Spec v5 section 8, FR-ACC-11..16, FR-TRN-20) through the real commands:
 * qualification from ten completed ATTENDED bookings, the automatic transitions the reconciler applies, the admin
 * actions and the protection of admin states. Every transition is a new row with its own {@code transition_id}; the
 * decision reads the stored {@code cause}; a revocation records its Tournament cascade and its admin alert once.
 */
class VerifiedPlayerIntegrationTest extends IntegrationTest {

    private static final String REASON = "Reviewed by support.";

    @Autowired
    ApplicationContext context;

    @Autowired
    AdminVerifiedPlayers admin;

    @Autowired
    TrustReconciler reconciler;

    @Autowired
    CommandRunner runner;

    VerifiedPlayerFlows flows;
    UUID adminId;
    CorrelationId.Scope correlation;

    @BeforeEach
    void setUp() {
        flows = VerifiedPlayerFlows.of(context);
        adminId = Fixtures.anAdmin().create().id();
        correlation = CorrelationId.open(CorrelationId.generate());
    }

    @AfterEach
    void tearDown() {
        correlation.close();
    }

    /**
     * Section 8.4 and E7-61's carry-forward: the tenth ATTENDED completion commits with the initial VERIFIED transition
     * (TRUST_QUALIFICATION), activated now - the ninth leaves the customer NOT_QUALIFIED and NOT_VERIFIED.
     */
    @Test
    void theTenthAttendedCompletionVerifiesNow() {
        BookingFlows.Customer customer = flows.bookings().customer();
        List<Booking> nine = flows.attendedCompletions(customer, 9);
        UUID user = nine.getFirst().customerId();
        assertThat(projection(user)).isEqualTo(new VerifiedPlayerFlows.Projection("NOT_QUALIFIED", 9, "NOT_VERIFIED"));
        assertThat(transitions(user)).isEmpty();

        Booking tenth = flows.attendedCompletions(customer, 1).getFirst();

        assertThat(projection(user)).isEqualTo(new VerifiedPlayerFlows.Projection("QUALIFIED", 10, "VERIFIED"));
        Transition verified = transitions(user).getFirst();
        assertThat(transitions(user)).hasSize(1);
        assertThat(verified.from()).isEqualTo("NOT_VERIFIED");
        assertThat(verified.to()).isEqualTo("VERIFIED");
        assertThat(verified.cause()).isEqualTo("TRUST_QUALIFICATION");
        assertThat(verified.adminId()).isNull();
        assertThat(verified.cascadeExecutedAt()).as("only a revocation cascades").isNull();
        // FR-ACC-13: never back-dated - the completion command's instant, after the tenth booking ended
        assertThat(verified.occurredAt()).isAfter(tenth.startAt()).isCloseTo(dbNow(), within(Duration.ofMinutes(1)));
        assertThat(transitionedEvents(user)).containsExactly(verified.transitionId().toString());
        assertThat(cascadeKeys(user)).isEmpty();
        assertThat(revokedAlerts(user)).isEmpty();
    }

    /**
     * THE NAMED ACCEPTANCE CASE (arch 07 section 6.2, FR-ACC-14): a correction removes qualification - VERIFIED ->
     * REVOKED, TRUST_CORRECTION, its cascade once - and a later correction restores it - REVOKED -> VERIFIED with no
     * admin action. The revocation and the regain carry two distinct transition IDs.
     */
    @Test
    void revokeByCorrectionThenRequalifyIsVerifiedAgainWithTwoTransitionIdsAndNoAdminAction() {
        List<Booking> ten = flows.attendedCompletions(10);
        UUID user = ten.getFirst().customerId();
        Booking corrected = ten.get(3);

        flows.attendance().correct(adminId, corrected.bookingId(), AttendanceResult.ATTENDANCE_UNCONFIRMED, REASON);

        assertThat(projection(user)).isEqualTo(new VerifiedPlayerFlows.Projection("NOT_QUALIFIED", 9, "REVOKED"));
        Transition revoked = transitions(user).getLast();
        assertThat(revoked.from()).isEqualTo("VERIFIED");
        assertThat(revoked.to()).isEqualTo("REVOKED");
        assertThat(revoked.cause()).isEqualTo("TRUST_CORRECTION");
        assertThat(revoked.adminId()).isNull();
        assertThat(revoked.cascadeExecutedAt()).as("section 8.9: the cascade ran with the revocation")
                .isEqualTo(revoked.occurredAt());
        assertThat(cascadeKeys(user)).containsExactly("VP_TOURNAMENT_CASCADE:" + revoked.transitionId());
        assertThat(revokedAlerts(user)).containsExactly(revoked.transitionId() + "|TRUST_CORRECTION");

        flows.attendance().correct(adminId, corrected.bookingId(), AttendanceResult.ATTENDED, REASON);

        assertThat(projection(user)).isEqualTo(new VerifiedPlayerFlows.Projection("QUALIFIED", 10, "VERIFIED"));
        List<Transition> all = transitions(user);
        assertThat(all).extracting(Transition::to).containsExactly("VERIFIED", "REVOKED", "VERIFIED");
        Transition regained = all.getLast();
        assertThat(regained.from()).isEqualTo("REVOKED");
        assertThat(regained.cause()).isEqualTo("TRUST_CORRECTION");
        assertThat(regained.transitionId()).as("two distinct transition IDs").isNotEqualTo(revoked.transitionId());
        assertThat(all).extracting(Transition::transitionId).doesNotHaveDuplicates();
        assertThat(all).as("no admin action anywhere").extracting(Transition::adminId).containsOnlyNulls();
        assertThat(all).extracting(Transition::cause).doesNotContain("ADMIN");
        // FR-ACC-13: the regain activates now, not at the historical qualification point
        assertThat(regained.occurredAt()).isAfter(revoked.occurredAt());
        // the cascade is the revocation's alone; the regain resurrects nothing (section 8.9)
        assertThat(cascadeKeys(user)).containsExactly("VP_TOURNAMENT_CASCADE:" + revoked.transitionId());
        assertThat(transitionedEvents(user)).hasSize(3);
    }

    /** Arch 07 section 4.1: reconciling a settled User again, in a new command, creates nothing and cascades nothing. */
    @Test
    void reconcilingAgainCreatesNoTransitionAndNoCascade() {
        List<Booking> ten = flows.attendedCompletions(10);
        UUID user = ten.getFirst().customerId();
        flows.attendance().correct(adminId, ten.get(0).bookingId(), AttendanceResult.ATTENDANCE_UNCONFIRMED, REASON);
        List<Transition> before = transitions(user);

        TrustReconciler.Reconciliation again = reconcile(user);

        assertThat(again.settled()).isTrue();
        assertThat(again.projectionRefreshed()).isFalse();
        assertThat(transitions(user)).isEqualTo(before);
        assertThat(cascadeKeys(user)).hasSize(1);
        assertThat(revokedAlerts(user)).hasSize(1);
    }

    /**
     * FR-ACC-15: an admin SUSPENDED User who loses and then regains qualification stays SUSPENDED - no automatic
     * revocation, no automatic regain. An explicit Restore then returns VERIFIED, as they are qualified.
     */
    @Test
    void anAdminSuspendedUserWhoRequalifiesStaysSuspended() {
        List<Booking> ten = flows.attendedCompletions(10);
        UUID user = ten.getFirst().customerId();
        AdminVerifiedPlayers.Player suspended = admin.moderate(adminId, user, REASON,
                VerifiedPlayerAuditAction.VERIFIED_PLAYER_SUSPENDED);
        assertThat(suspended.status().name()).isEqualTo("SUSPENDED");
        assertThat(cascadeKeys(user)).as("section 37.8: suspension cancels nothing").isEmpty();

        flows.attendance().correct(adminId, ten.get(5).bookingId(), AttendanceResult.ATTENDANCE_UNCONFIRMED, REASON);
        assertThat(projection(user)).isEqualTo(new VerifiedPlayerFlows.Projection("NOT_QUALIFIED", 9, "SUSPENDED"));
        flows.attendance().correct(adminId, ten.get(5).bookingId(), AttendanceResult.ATTENDED, REASON);
        assertThat(projection(user)).isEqualTo(new VerifiedPlayerFlows.Projection("QUALIFIED", 10, "SUSPENDED"));
        assertThat(transitions(user)).extracting(Transition::to).containsExactly("VERIFIED", "SUSPENDED");

        AdminVerifiedPlayers.Player restored = admin.moderate(adminId, user, REASON,
                VerifiedPlayerAuditAction.VERIFIED_PLAYER_RESTORED);
        assertThat(restored.status().name()).isEqualTo("VERIFIED");
        assertThat(restored.statusCause().name()).isEqualTo("ADMIN");
        Transition last = transitions(user).getLast();
        assertThat(last.cause()).isEqualTo("ADMIN");
        assertThat(last.adminId()).isEqualTo(adminId);
        assertThat(audits(user, "VERIFIED_PLAYER_SUSPENDED")).isOne();
        assertThat(audits(user, "VERIFIED_PLAYER_RESTORED")).isOne();
    }

    /**
     * FR-ACC-15 and section 8.8: an admin REVOKED User (cascade and alert with cause ADMIN) who regains qualification
     * stays REVOKED; Restore while NOT_QUALIFIED is NOT_VERIFIED.
     */
    @Test
    void anAdminRevokedUserWhoRequalifiesStaysRevokedAndRestoreFollowsQualification() {
        List<Booking> ten = flows.attendedCompletions(10);
        UUID user = ten.getFirst().customerId();
        admin.moderate(adminId, user, REASON, VerifiedPlayerAuditAction.VERIFIED_PLAYER_REVOKED);
        Transition revoked = transitions(user).getLast();
        assertThat(revoked.cause()).isEqualTo("ADMIN");
        assertThat(cascadeKeys(user)).containsExactly("VP_TOURNAMENT_CASCADE:" + revoked.transitionId());
        assertThat(revokedAlerts(user)).containsExactly(revoked.transitionId() + "|ADMIN");

        flows.attendance().correct(adminId, ten.get(2).bookingId(), AttendanceResult.ATTENDANCE_UNCONFIRMED, REASON);
        flows.attendance().correct(adminId, ten.get(2).bookingId(), AttendanceResult.ATTENDED, REASON);
        assertThat(projection(user).status()).as("never regained automatically").isEqualTo("REVOKED");
        flows.attendance().correct(adminId, ten.get(2).bookingId(), AttendanceResult.ATTENDANCE_UNCONFIRMED, REASON);
        assertThat(projection(user)).isEqualTo(new VerifiedPlayerFlows.Projection("NOT_QUALIFIED", 9, "REVOKED"));

        AdminVerifiedPlayers.Player restored = admin.moderate(adminId, user, REASON,
                VerifiedPlayerAuditAction.VERIFIED_PLAYER_RESTORED);

        assertThat(restored.status().name()).isEqualTo("NOT_VERIFIED");
        assertThat(transitions(user)).extracting(Transition::to)
                .containsExactly("VERIFIED", "REVOKED", "NOT_VERIFIED");
        assertThat(cascadeKeys(user)).as("one cascade per revocation").hasSize(1);
    }

    /** PO decision D5: suspend and revoke only from VERIFIED, restore only from SUSPENDED or REVOKED. */
    @Test
    void anActionFromAnotherStatusIsNotAllowedAndChangesNothing() {
        UUID fresh = Fixtures.aUser().verified().create().id();
        for (VerifiedPlayerAuditAction action : VerifiedPlayerAuditAction.values()) {
            assertThatThrownBy(() -> admin.moderate(adminId, fresh, REASON, action))
                    .isInstanceOfSatisfying(ApiException.class,
                            refused -> assertThat(refused.code()).isEqualTo(ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED));
        }
        assertThat(transitions(fresh)).isEmpty();

        List<Booking> ten = flows.attendedCompletions(10);
        UUID verified = ten.getFirst().customerId();
        assertThatThrownBy(() -> admin.moderate(adminId, verified, REASON,
                VerifiedPlayerAuditAction.VERIFIED_PLAYER_RESTORED))
                .isInstanceOfSatisfying(ApiException.class,
                        refused -> assertThat(refused.code()).isEqualTo(ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED));
        admin.moderate(adminId, verified, REASON, VerifiedPlayerAuditAction.VERIFIED_PLAYER_SUSPENDED);
        assertThatThrownBy(() -> admin.moderate(adminId, verified, REASON,
                VerifiedPlayerAuditAction.VERIFIED_PLAYER_REVOKED))
                .as("SUSPENDED is not VERIFIED")
                .isInstanceOfSatisfying(ApiException.class,
                        refused -> assertThat(refused.code()).isEqualTo(ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED));
        assertThat(transitions(verified)).hasSize(2);
    }

    /** Section 8.8: a reason is required; an unknown Customer is NOT_FOUND. */
    @Test
    void aReasonAndAKnownCustomerAreRequired() {
        UUID fresh = Fixtures.aUser().verified().create().id();
        assertThatThrownBy(() -> admin.moderate(adminId, fresh, "  ", VerifiedPlayerAuditAction.VERIFIED_PLAYER_REVOKED))
                .isInstanceOfSatisfying(ApiException.class,
                        refused -> assertThat(refused.code()).isEqualTo(ApiErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> admin.moderate(adminId, UUID.randomUUID(), REASON,
                VerifiedPlayerAuditAction.VERIFIED_PLAYER_REVOKED))
                .isInstanceOfSatisfying(ApiException.class,
                        refused -> assertThat(refused.code()).isEqualTo(ApiErrorCode.NOT_FOUND));
    }

    /**
     * Section 8.2: progress counts completed ATTENDED bookings only - an ATTENDANCE_UNCONFIRMED completion (no check-in)
     * and a no-show add nothing.
     */
    @Test
    void onlyCompletedAttendedBookingsCount() {
        BookingFlows.Customer customer = flows.bookings().customer();
        List<Booking> nine = flows.attendedCompletions(customer, 9);
        UUID user = nine.getFirst().customerId();

        Booking unconfirmed = flows.bookings().pastItsEnd(customer.nextActive(Duration.ofMinutes(5)),
                Duration.ofMinutes(10));
        assertThat(flows.bookings().fireCompletion(unconfirmed)).isTrue();
        Booking missed = customer.nextActive(Duration.ofMinutes(20));
        flows.attendance().noShow(missed.venueAccountId(), missed.bookingId());

        assertThat(projection(user)).isEqualTo(new VerifiedPlayerFlows.Projection("NOT_QUALIFIED", 9, "NOT_VERIFIED"));
        assertThat(transitions(user)).isEmpty();
    }

    /**
     * Section 8.5: a correction that discovers the qualification (the tenth ATTENDED is a corrected one) activates
     * VERIFIED now with TRUST_CORRECTION, its own transition ID.
     */
    @Test
    void aQualificationDiscoveredByCorrectionVerifiesNowByCorrection() {
        BookingFlows.Customer customer = flows.bookings().customer();
        List<Booking> nine = flows.attendedCompletions(customer, 9);
        UUID user = nine.getFirst().customerId();
        Booking unconfirmed = flows.bookings().pastItsEnd(customer.nextActive(Duration.ofMinutes(5)),
                Duration.ofMinutes(10));
        assertThat(flows.bookings().fireCompletion(unconfirmed)).isTrue();
        assertThat(transitions(user)).isEmpty();

        flows.attendance().correct(adminId, unconfirmed.bookingId(), AttendanceResult.ATTENDED, REASON);

        assertThat(projection(user)).isEqualTo(new VerifiedPlayerFlows.Projection("QUALIFIED", 10, "VERIFIED"));
        Transition verified = transitions(user).getFirst();
        assertThat(verified.cause()).isEqualTo("TRUST_CORRECTION");
        assertThat(verified.from()).isEqualTo("NOT_VERIFIED");
        assertThat(verified.occurredAt()).isCloseTo(dbNow(), within(Duration.ofMinutes(1)));
    }

    private TrustReconciler.Reconciliation reconcile(UUID user) {
        return runner.run(new Command<TrustReconciler.Reconciliation>() {
            @Override
            public EligibilityCategory eligibilityCategory() {
                return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
            }

            @Override
            public CommandScope scope() {
                return CommandScope.shared(LockSet.builder().users(user).build());
            }

            @Override
            public TrustReconciler.Reconciliation execute(CommandContext command) {
                return reconciler.reconcile(command, user);
            }
        });
    }

    private static long audits(UUID user, String action) {
        return OWNER.sql("""
                        SELECT count(*) FROM audit.audit_event
                         WHERE subject_type = 'USER' AND subject_id = :user AND action = :action
                           AND NULLIF(btrim(reason), '') IS NOT NULL""")
                .param("user", user).param("action", action).query(Long.class).single();
    }

    private static Instant dbNow() {
        return OWNER.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single().toInstant();
    }

    private static org.assertj.core.data.TemporalUnitOffset within(Duration duration) {
        return new org.assertj.core.data.TemporalUnitWithinOffset(duration.toSeconds(),
                java.time.temporal.ChronoUnit.SECONDS);
    }
}
