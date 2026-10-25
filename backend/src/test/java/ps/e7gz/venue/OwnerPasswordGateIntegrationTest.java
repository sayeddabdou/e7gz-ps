package ps.e7gz.venue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.venue.VenueAccountFixtures.OWNER;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ps.e7gz.audit.api.OwnerPasswordCheck;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.security.owner.OwnerProtected;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.venue.api.OwnerPasswordGate;

/**
 * E7-114, PO decisions Q2 and Q3 (24 Oct 2026), on the shared Owner Password gate with probe commands:
 * <ul>
 *   <li>Q2 - a right password whose action is then refused leaves exactly one OWNER_PASSWORD_ACCEPTED row (in its own
 *       transaction, with the action id and the refusal's code), never counted; a refusal before the check, a missing
 *       or blank password, a wrong one and an unexpected failure leave none;</li>
 *   <li>the attempt tracking - reset before every attempt (a 40001 rerun refused before its check is not taken for a
 *       checked one), a refusal at commit counted for the attempt that checked, nothing carried to the next run, another
 *       thread or a nested command;</li>
 *   <li>Q3's {@code run} with the password - checked before anything the command does; {@code verified} only after.</li>
 * </ul>
 */
class OwnerPasswordGateIntegrationTest extends IntegrationTest {

    private static final String PIN = "1234";
    private static final String ACTION = "VENUE_PRICING_CHANGE";
    private static final String RAISE_40001 =
            "DO $$ BEGIN RAISE EXCEPTION USING ERRCODE = '40001', MESSAGE = 'test serialization failure'; END $$";

    @Autowired
    OwnerPasswordGate gate;

    @Autowired
    PasswordEncoder passwords;

    private VenueAccountFixtures.VenueWithAccount venue;
    private UUID account;
    private CorrelationId.Scope correlation;

    @BeforeEach
    void aVenueAccountWithItsPin() {
        venue = VenueAccountFixtures.venueWithAccount(passwords, PIN);
        account = venue.accountId();
        correlation = CorrelationId.open(UUID.randomUUID());
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    // ------------------------------------------------------------------------------------------ Q2: the audit

    @Test
    void aRightPinThenABusinessRefusalIsAuditedOnceAsAcceptedAndNotCounted() {
        ApiException refusal = new ApiException(ApiErrorCode.BOOKING_STATE_CHANGED);

        assertThatThrownBy(() -> gate.run(probe(context -> {
            gate.verify(context, account, PIN);
            throw refusal;
        }), account, CorrelationId.require())).isSameAs(refusal);

        assertThat(accepted()).containsExactly("SUCCESS " + ACTION + " BOOKING_STATE_CHANGED");
        assertThat(refusals()).isZero();
        assertThat(failures()).as("an accepted PIN is not an attempt").isZero();
    }

    @Test
    void withThePasswordTheGateChecksFirstAndARefusalAfterItIsAuditedOnce() {
        assertThatThrownBy(() -> gate.run(probe(context -> {
            assertThat(gate.verified(context, account)).isEqualTo(OwnerPasswordCheck.succeeded());
            throw new ApiException(ApiErrorCode.AFFECTED_SET_CHANGED_REFRESH_PREVIEW);
        }), account, PIN, CorrelationId.require()))
                .isInstanceOf(ApiException.class);

        assertThat(accepted()).containsExactly("SUCCESS " + ACTION + " AFFECTED_SET_CHANGED_REFRESH_PREVIEW");
        assertThat(acceptedPayloadKeys()).containsExactly("protectedAction", "refusalCode");
        assertThat(failures()).isZero();
    }

    @Test
    void aSuccessLeavesNoRowOfTheGate() {
        assertThat(gate.run(probe(context -> {
            gate.verified(context, account);
            return "done";
        }), account, PIN, CorrelationId.require())).isEqualTo("done");

        assertThat(accepted()).isEmpty();
        assertThat(refusals()).isZero();
    }

    @Test
    void aRefusalBeforeTheCheckIsNotAuditedAsAccepted() {
        assertThatThrownBy(() -> gate.run(probe(context -> {
            throw new ApiException(ApiErrorCode.NOT_FOUND);
        }), account, CorrelationId.require())).isInstanceOf(ApiException.class);

        assertThat(accepted()).isEmpty();
        assertThat(refusals()).isZero();
    }

    @Test
    void aMissingOrBlankPinIsRequiredAndAWrongOneIsRefusedNeverAccepted() {
        AtomicBoolean ran = new AtomicBoolean();
        for (String missing : new String[] {null, "", "   "}) {
            assertThat(code(() -> gate.run(probe(context -> {
                ran.set(true);
                return "done";
            }), account, missing, CorrelationId.require()))).isEqualTo(ApiErrorCode.OWNER_PASSWORD_REQUIRED);
        }
        assertThat(failures()).as("no value sent is not a guess").isZero();

        assertThat(code(() -> gate.run(probe(context -> {
            ran.set(true);
            return "done";
        }), account, "9999", CorrelationId.require()))).isEqualTo(ApiErrorCode.OWNER_PASSWORD_INVALID);

        assertThat(ran).as("the command never ran").isFalse();
        assertThat(accepted()).isEmpty();
        assertThat(refusals()).isOne();
        assertThat(failures()).isOne();
    }

    /** A database guard with a code (a DataAccessException, as the API maps it) is a refusal too: audited with it. */
    @Test
    void aDatabaseGuardAfterARightPinIsAuditedOnceWithItsCode() {
        assertThatThrownBy(() -> gate.run(probe(context -> {
            gate.verified(context, account);
            context.jdbc().sql("DO $$ BEGIN RAISE EXCEPTION USING ERRCODE = 'P0001', MESSAGE = 'test guard', "
                    + "CONSTRAINT = 'gd_reservation_conflict_booking'; END $$").update();
            return "never";
        }), account, PIN, CorrelationId.require())).isInstanceOf(DataAccessException.class);

        assertThat(accepted()).containsExactly("SUCCESS " + ACTION + " SLOT_NO_LONGER_AVAILABLE");
        assertThat(failures()).isZero();
    }

    @Test
    void anUnexpectedFailureAfterARightPinIsNotAuditedAsARefusal() {
        assertThatThrownBy(() -> gate.run(probe(context -> {
            gate.verified(context, account);
            throw new IllegalStateException("a defect");
        }), account, PIN, CorrelationId.require())).isInstanceOf(IllegalStateException.class).hasMessage("a defect");

        assertThat(accepted()).isEmpty();
        assertThat(refusals()).isZero();
    }

    // ------------------------------------------------------------------------------------------ attempts and retries

    /**
     * The critical case: attempt 1 checks the PIN, then fails with 40001; attempt 2 is refused before its check. The
     * refusal is not one after a right PIN: no OWNER_PASSWORD_ACCEPTED, nothing counted. Here attempt 2 is refused
     * before its transaction ({@code beforeAttempt}), so only the reset there - not one in {@code execute} - tells.
     */
    @Test
    void aRerunAfter40001RefusedBeforeItsTransactionIsNotAuditedAsAccepted() {
        AtomicBoolean firstChecked = new AtomicBoolean();

        assertThat(code(() -> gate.run(probe(attempt -> {
            if (attempt == 2) {
                throw new ApiException(ApiErrorCode.BOOKING_STATE_CHANGED);
            }
        }, context -> {
            gate.verified(context, account);
            firstChecked.set(true);
            context.jdbc().sql(RAISE_40001).update();
            return "never";
        }), account, PIN, CorrelationId.require()))).isEqualTo(ApiErrorCode.BOOKING_STATE_CHANGED);

        assertThat(firstChecked).as("attempt 1 checked the PIN").isTrue();
        assertThat(accepted()).isEmpty();
        assertThat(refusals()).isZero();
        assertThat(failures()).isZero();
    }

    /** The same with the PIN checked by the command itself: attempt 2 is refused in its body, before its check. */
    @Test
    void aRerunAfter40001RefusedBeforeTheCommandsOwnCheckIsNotAuditedAsAccepted() {
        assertThat(code(() -> gate.run(probe(context -> {
            if (context.attempt() == 1) {
                gate.verify(context, account, PIN);
                context.jdbc().sql(RAISE_40001).update();
            }
            throw new ApiException(ApiErrorCode.BOOKING_STATE_CHANGED);
        }), account, CorrelationId.require()))).isEqualTo(ApiErrorCode.BOOKING_STATE_CHANGED);

        assertThat(accepted()).isEmpty();
        assertThat(failures()).isZero();
    }

    /** The reset loses no genuine one: a rerun that checks the PIN and is then refused is audited once. */
    @Test
    void aRerunAfter40001ThatChecksAndIsRefusedIsAuditedOnce() {
        assertThat(code(() -> gate.run(probe(context -> {
            gate.verified(context, account);
            if (context.attempt() == 1) {
                context.jdbc().sql(RAISE_40001).update();
            }
            throw new ApiException(ApiErrorCode.BOOKING_STATE_CHANGED);
        }), account, PIN, CorrelationId.require()))).isEqualTo(ApiErrorCode.BOOKING_STATE_CHANGED);

        assertThat(accepted()).containsExactly("SUCCESS " + ACTION + " BOOKING_STATE_CHANGED");
        assertThat(failures()).isZero();
    }

    /**
     * A refusal raised while the transaction commits, after the body returned (as a deferred guard would): it ends the
     * attempt that checked the PIN, so it is audited once as accepted, and nothing of the attempt commits.
     */
    @Test
    void aRefusalAtCommitAfterARightPinIsAuditedOnceAsAccepted() {
        assertThat(code(() -> gate.run(probe(context -> {
            gate.verified(context, account);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    throw new ApiException(ApiErrorCode.BOOKING_STATE_CHANGED);
                }
            });
            return "returned";
        }), account, PIN, CorrelationId.require()))).isEqualTo(ApiErrorCode.BOOKING_STATE_CHANGED);

        assertThat(accepted()).containsExactly("SUCCESS " + ACTION + " BOOKING_STATE_CHANGED");
        assertThat(failures()).isZero();
    }

    // ------------------------------------------------------------------------------------------ no state leaks

    @Test
    void verifiedNeedsTheGatesOwnCheckOfThisAccountInThisAttempt() {
        assertThatThrownBy(() -> gate.run(probe(context -> {
            gate.verified(context, account);
            return "never";
        }), account, CorrelationId.require())).isInstanceOf(IllegalStateException.class);

        UUID other = VenueAccountFixtures.venueWithAccount(passwords, PIN).accountId();
        assertThatThrownBy(() -> gate.run(probe(context -> {
            gate.verified(context, other);
            return "never";
        }), account, PIN, CorrelationId.require())).isInstanceOf(IllegalStateException.class);

        assertThat(accepted()).isEmpty();
    }

    @Test
    void nothingCarriesOverToTheNextRunOnTheSameThread() {
        gate.run(probe(context -> gate.verified(context, account).outcome().name()), account, PIN,
                CorrelationId.require());
        assertThatThrownBy(() -> gate.run(probe(context -> {
            gate.verify(context, account, PIN);
            throw new ApiException(ApiErrorCode.BOOKING_STATE_CHANGED);
        }), account, CorrelationId.require())).isInstanceOf(ApiException.class);

        assertThatThrownBy(() -> gate.run(probe(context -> {
            gate.verified(context, account);
            return "never";
        }), account, CorrelationId.require())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> gate.run(probe(context -> {
            throw new ApiException(ApiErrorCode.BOOKING_STATE_CHANGED);
        }), account, CorrelationId.require())).isInstanceOf(ApiException.class);

        assertThat(accepted()).as("only the run that checked and was refused").hasSize(1);
    }

    @Test
    void anotherThreadNeverSeesAnAttemptInFlight() throws Exception {
        UUID other = VenueAccountFixtures.venueWithAccount(passwords, PIN).accountId();
        CountDownLatch checked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> holder = pool.submit(() -> inCorrelation(() -> gate.run(probe(context -> {
                gate.verified(context, account);
                checked.countDown();
                await(release);
                return "done";
            }), account, PIN, CorrelationId.require())));
            assertThat(checked.await(30, TimeUnit.SECONDS)).isTrue();

            Future<String> intruder = pool.submit(() -> inCorrelation(() -> gate.run(probe(context -> {
                gate.verified(context, other);
                return "never";
            }), other, CorrelationId.require())));
            assertThatThrownBy(() -> intruder.get(30, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);

            release.countDown();
            assertThat(holder.get(30, TimeUnit.SECONDS)).isEqualTo("done");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void aProtectedCommandCannotRunAnotherInside() {
        assertThatThrownBy(() -> gate.run(probe(context -> {
            gate.verified(context, account);
            return gate.run(probe(inner -> "never"), account, PIN, CorrelationId.require());
        }), account, PIN, CorrelationId.require()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("inside another protected command");

        assertThat(accepted()).isEmpty();
    }

    // ------------------------------------------------------------------------------------------ helpers

    private static Probe probe(Function<CommandContext, String> body) {
        return probe(attempt -> {
        }, body);
    }

    private static Probe probe(IntConsumer beforeAttempt, Function<CommandContext, String> body) {
        return new Probe(beforeAttempt, body);
    }

    /** TEST-ONLY: a protected command whose hook and body are given. No locks. */
    @OwnerProtected(ACTION)
    private record Probe(IntConsumer before, Function<CommandContext, String> body) implements Command<String> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.empty());
        }

        @Override
        public void beforeAttempt(int attempt) {
            before.accept(attempt);
        }

        @Override
        public String execute(CommandContext context) {
            return body.apply(context);
        }
    }

    private static ApiErrorCode code(Runnable action) {
        try {
            action.run();
        } catch (ApiException e) {
            return e.code();
        }
        throw new AssertionError("expected an ApiException");
    }

    private static <T> T inCorrelation(java.util.concurrent.Callable<T> action) throws Exception {
        try (CorrelationId.Scope ignored = CorrelationId.open(UUID.randomUUID())) {
            return action.call();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** The gate's and commands' OWNER_PASSWORD_ACCEPTED rows of the account: "outcome action refusalCode". */
    private List<String> accepted() {
        return OWNER.sql("""
                        SELECT owner_password_outcome || ' ' || (payload->>'protectedAction') || ' '
                               || coalesce(payload->>'refusalCode', '-')
                          FROM audit.audit_event
                         WHERE action = 'OWNER_PASSWORD_ACCEPTED' AND subject_id = :account AND actor_type = 'VENUE'
                           AND actor_id = :account AND owner_password_used ORDER BY occurred_at""")
                .param("account", account).query(String.class).list();
    }

    private List<String> acceptedPayloadKeys() {
        return OWNER.sql("""
                        SELECT k FROM audit.audit_event, jsonb_object_keys(payload) k
                         WHERE action = 'OWNER_PASSWORD_ACCEPTED' AND subject_id = :account ORDER BY k""")
                .param("account", account).query(String.class).list();
    }

    private long refusals() {
        return OWNER.sql("""
                        SELECT count(*) FROM audit.audit_event
                         WHERE action = 'OWNER_PASSWORD_REFUSED' AND subject_id = :account""")
                .param("account", account).query(Long.class).single();
    }

    /** The account's counted wrong PINs (identity's OWNER_PASSWORD rate-limit row; 0 without one). */
    private long failures() {
        return OWNER.sql("""
                        SELECT coalesce((SELECT counter FROM iam.security_rate_limit_counter
                                          WHERE limiter_type = 'OWNER_PASSWORD'
                                            AND subject_key_hash = encode(sha256(convert_to(
                                                    'venue-account:' || CAST(:account AS text), 'UTF8')), 'hex')), 0)""")
                .param("account", account).query(Long.class).single();
    }
}
