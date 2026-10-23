package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.codes.FeeTreatment;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.codes.WalletTransactionCause;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.money.Money;
import ps.e7gz.common.persistence.DataSourceConfig;
import ps.e7gz.loyalty.api.BookingFeeRefunds;
import ps.e7gz.loyalty.api.FeeQuery;
import ps.e7gz.loyalty.internal.LoyaltyLedgers;
import ps.e7gz.scheduling.api.ScheduledJobs;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.fixtures.Fixtures;

/**
 * NFR-38 (arch 11 section 7; E7-66, PO decision 23 Oct 2026): with a non-zero Booking Fee every terminal reason still
 * resolves to exactly one fee treatment with the correct refund, and a refund of a collected fee lands in the wallet -
 * so switching the fee on needs no redesign (FR-PTS-13 to FR-PTS-18).
 *
 * <p>The refund reads only the fee the booking stored at creation ({@code booking_fee_snapshot}; FR-PTS-13: "each
 * booking stores the fee applied at creation"). Changing {@code booking.fee} itself would leak into every other test on
 * the shared database, so each booking here is created by the real command (at the launch fee, EGP 0) and its stored
 * fee is then set to {@value #FEE}. The terminal state is planted ({@link BookingStates}), because most of these
 * reasons have no command yet (emergencies, owner review, bans and admin actions are later sprints), and the decision
 * is made by the production producer, {@code loyalty.record_refund}, through {@link BookingFeeRefunds} - or, for the
 * three ADMIN_* reasons, with the Platform Admin's audited service-loss assessment the database requires.
 */
class BookingFeeNonZeroIntegrationTest extends IntegrationTest {

    static final String FEE = "5.00";
    static final BigDecimal ZERO = new BigDecimal("0.00");

    @Autowired
    CommandRunner runner;

    @Autowired
    OnlineBookingCreation creation;

    @Autowired
    ManualBookings manual;

    @Autowired
    BookingFeeRefunds refunds;

    @Autowired
    ScheduledJobs jobs;

    @Autowired
    LoyaltyLedgers ledgers;

    @Autowired
    @Qualifier(DataSourceConfig.ADMIN_DATA_SOURCE)
    DataSource adminDataSource;

    private CorrelationId.Scope correlation;
    private OnlineBookingWorld world;

    @BeforeEach
    void world() {
        correlation = CorrelationId.open(CorrelationId.generate());
        world = OnlineBookingWorld.create();
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    @ParameterizedTest
    @EnumSource(value = BookingTerminalReason.class, mode = EnumSource.Mode.EXCLUDE,
            names = {"CUSTOMER_CANCELLED", "USER_BANNED", "VENUE_CANCELLED_MANUAL_BOOKING",
                     "ADMIN_CANCELLED", "ADMIN_CORRECTION", "ADMIN_NO_SHOW_REVERSAL"})
    void anOperationalReasonRefundsTheWholeFee(BookingTerminalReason reason) {
        // FR-PTS-14, FR-PTS-15 (FR-PTS-17 for the Late Confirm decline and timeout): a full refund, whenever it ends
        Planted booking = plant(reason, Duration.ofMinutes(40));

        UUID refund = decide(booking);

        assertThat(refund(refund)).isEqualTo(new Refund(FeeTreatment.FULL_REFUND.name(), reason.name(),
                new BigDecimal(FEE), new BigDecimal(FEE)));
        assertThat(FeeQuery.feeTreatmentFor(reason)).isEqualTo(FeeTreatment.FULL_REFUND);
    }

    @ParameterizedTest
    @EnumSource(value = BookingTerminalReason.class, names = {"CUSTOMER_CANCELLED", "USER_BANNED"})
    void theCustomerPolicyRefundsOnlyWithinFifteenMinutesOfCreation(BookingTerminalReason reason) {
        // FR-PTS-14: refundable only within 15 minutes of creation and before start
        Planted inTime = plant(reason, Duration.ofMinutes(15));
        Planted late = plant(reason, Duration.ofMinutes(16));

        assertThat(refund(decide(inTime))).isEqualTo(new Refund(FeeTreatment.CUSTOMER_POLICY.name(), reason.name(),
                new BigDecimal(FEE), new BigDecimal(FEE)));
        assertThat(refund(decide(late))).isEqualTo(new Refund(FeeTreatment.CUSTOMER_POLICY.name(), reason.name(),
                new BigDecimal(FEE), ZERO));
        assertThat(FeeQuery.feeTreatmentFor(reason)).isEqualTo(FeeTreatment.CUSTOMER_POLICY);
    }

    @ParameterizedTest
    @EnumSource(value = BookingTerminalReason.class,
            names = {"ADMIN_CANCELLED", "ADMIN_CORRECTION", "ADMIN_NO_SHOW_REVERSAL"})
    void anAdminReasonRefundsOnlyAConfirmedServiceLoss(BookingTerminalReason reason) {
        // FR-PTS-15: "admin cancellation or correction where the customer is not at fault"; FR-PTS-18: the refund
        // follows the failure to deliver the service, not the reclassification
        UUID admin = Fixtures.anAdmin().create().id();
        Planted lost = plant(reason, Duration.ofMinutes(40));
        Planted notEstablished = plant(reason, Duration.ofMinutes(40));

        assertThat(refund(decideAsAdmin(admin, lost, true))).isEqualTo(new Refund(FeeTreatment.FULL_REFUND.name(),
                reason.name(), new BigDecimal(FEE), new BigDecimal(FEE)));
        assertThat(refund(decideAsAdmin(admin, notEstablished, false))).isEqualTo(new Refund(
                FeeTreatment.FULL_REFUND.name(), reason.name(), new BigDecimal(FEE), ZERO));
    }

    @Test
    void aManualBookingHasNoFeeAndNoRefundDecision() {
        // FR-PTS-16: VENUE_CANCELLED_MANUAL_BOOKING is NOT_APPLICABLE because no customer fee exists
        UUID bookingId = manual.create(world.account.id(), ManualBookingRequest.of(world.room.id(), PlayMode.SINGLE,
                world.start(), 60, null, null)).bookingId();

        manual.cancel(world.account.id(), bookingId);

        assertThat(VenueAnswerDb.OWNER.sql("""
                        SELECT terminal_reason || ' ' || fee_treatment FROM booking.booking WHERE id = :id""")
                .param("id", bookingId).query(String.class).single())
                .isEqualTo("VENUE_CANCELLED_MANUAL_BOOKING NOT_APPLICABLE");
        assertThat(VenueAnswerDb.OWNER.sql("SELECT count(*) FROM loyalty.booking_fee_refund WHERE booking_id = :id")
                .param("id", bookingId).query(Integer.class).single()).isZero();
        assertThat(FeeQuery.feeTreatmentFor(BookingTerminalReason.VENUE_CANCELLED_MANUAL_BOOKING))
                .isEqualTo(FeeTreatment.NOT_APPLICABLE);
    }

    @Test
    void aCollectedFeeIsRefundedIntoTheWalletWhenTheWalletSwitchIsOn() {
        // FR-PTS-10, PCR-004: the wallet is credited only by Booking Fee refunds. The receipt comes from a settlement
        // adapter that does not exist at launch (ADR-018), so the test records it the way that adapter will.
        UUID admin = Fixtures.anAdmin().create().id();
        Planted booking = plant(BookingTerminalReason.VENUE_REJECTED, Duration.ofMinutes(5));
        BigDecimal before = walletBalance(booking.customerId());
        walletSwitch(admin, true);
        try {
            UUID refund = runner.run(new Shared<>(booking.customerId(), context -> {
                context.jdbc().sql("SELECT loyalty.begin_command(ARRAY[CAST(:user AS uuid)], :correlation)")
                        .param("user", booking.customerId()).param("correlation", CorrelationId.require())
                        .query().singleRow();
                context.jdbc().sql("""
                                SELECT loyalty.record_fee_receipt(:booking, CAST(:amount AS numeric), 'TEST_ADAPTER',
                                                                  :reference, :providerAt)""")
                        .param("booking", booking.bookingId()).param("amount", FEE)
                        .param("reference", "nfr38-" + booking.bookingId())
                        .param("providerAt", utc(booking.createdAt().plus(Duration.ofMinutes(1))))
                        .query().singleRow();
                return refunds.recordDecision(context, booking.customerId(), booking.bookingId(),
                        booking.terminalHistoryId());
            }));

            assertThat(refund(refund).amount()).isEqualByComparingTo(FEE);
            assertThat(VenueAnswerDb.OWNER.sql("""
                            SELECT w.amount FROM loyalty.wallet_transaction w
                              JOIN loyalty.booking_fee_refund f ON f.wallet_transaction_id = w.id
                             WHERE f.id = :refund AND w.cause = 'BOOKING_FEE_REFUND' AND w.booking_id = :booking""")
                    .param("refund", refund).param("booking", booking.bookingId()).query(BigDecimal.class).single())
                    .isEqualByComparingTo(FEE);
            assertThat(walletBalance(booking.customerId())).isEqualByComparingTo(before.add(new BigDecimal(FEE)));
            // PCR-004: the customer's read-only wallet shows the refund and the balance
            LoyaltyLedgers.WalletHistory wallet = ledgers.wallet(booking.customerId(), null, null);
            assertThat(wallet.balance()).isEqualTo(Money.of(before.add(new BigDecimal(FEE))));
            assertThat(wallet.page().items()).singleElement().satisfies(credit -> {
                assertThat(credit.cause()).isEqualTo(WalletTransactionCause.BOOKING_FEE_REFUND);
                assertThat(credit.amount()).isEqualTo(Money.of(FEE));
                assertThat(credit.bookingId()).isEqualTo(booking.bookingId());
            });
        } finally {
            walletSwitch(admin, false);
        }
    }

    @Test
    void anUncollectedFeeIsDecidedButNeverCreditedToTheWallet() {
        // settle_refund: "a refund obligation may wait for a verified collection receipt; it is not a fabricated
        // Wallet credit" - at launch nothing is collected, so nothing is credited
        Planted booking = plant(BookingTerminalReason.VENUE_REJECTED, Duration.ofMinutes(5));
        BigDecimal before = walletBalance(booking.customerId());

        UUID refund = decide(booking);

        assertThat(refund(refund).amount()).isEqualByComparingTo(FEE);
        assertThat(VenueAnswerDb.OWNER.sql("SELECT wallet_transaction_id FROM loyalty.booking_fee_refund WHERE id = :x")
                .param("x", refund).query(UUID.class).list()).containsExactly((UUID) null);
        assertThat(walletBalance(booking.customerId())).isEqualByComparingTo(before);
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    /** An Online Booking with a {@value #FEE} fee, ended for {@code reason} {@code after} its creation. */
    record Planted(UUID bookingId, UUID customerId, Instant createdAt, UUID terminalHistoryId) {
    }

    record Refund(String treatment, String terminalReason, BigDecimal feeSnapshot, BigDecimal amount) {
    }

    /**
     * A new Customer's PENDING booking from the real creation command, created an hour ago with a {@value #FEE} fee,
     * then ended for {@code reason} at {@code createdAt + after} - before its start, which is an hour from now.
     */
    private Planted plant(BookingTerminalReason reason, Duration after) {
        UUID customer = world.anotherCustomer();
        CreatedBooking created = creation.create(world.request(customer, world.room, world.start(), 60));
        Instant createdAt = VenueAnswerDb.dbNow().minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS);
        // T1 withdrawn, as every real ending of a PENDING booking withdraws it
        runner.run(new Shared<>(LockSet.builder().users(customer).venues(world.venue.id()).resources(world.room.id())
                .build(),
                context -> jobs.supersede(context, VenueAnswers.venueResponseExpiryKey(created.bookingId()))));
        VenueAnswerDb.withoutTriggers(jdbc -> {
            jdbc.sql("""
                            UPDATE booking.booking
                               SET booking_fee_snapshot = CAST(:fee AS numeric),
                                   total_amount = venue_price_total + CAST(:fee AS numeric), created_at = :created
                             WHERE id = :id""")
                    .param("fee", FEE).param("created", utc(createdAt)).param("id", created.bookingId()).update();
            jdbc.sql("""
                            UPDATE booking.booking_history SET occurred_at = :created
                             WHERE booking_id = :id AND sequence = 1""")
                    .param("created", utc(createdAt)).param("id", created.bookingId()).update();
        });
        String status = reason == BookingTerminalReason.VENUE_REJECTED
                || reason == BookingTerminalReason.REJECTED_AFTER_EXPIRY ? "REJECTED" : "CANCELLED";
        BookingStates.plant(created.bookingId(), BookingStates.Plant.of(status).reason(reason.name())
                .terminalAt(createdAt.plus(after)));
        UUID history = VenueAnswerDb.OWNER.sql("""
                        SELECT id FROM booking.booking_history WHERE booking_id = :id ORDER BY sequence DESC LIMIT 1""")
                .param("id", created.bookingId()).query(UUID.class).single();
        return new Planted(created.bookingId(), customer, createdAt, history);
    }

    /** The production decision of every non-admin path: {@code loyalty.record_refund} through the Loyalty API. */
    private UUID decide(Planted booking) {
        return runner.run(new Shared<>(booking.customerId(), context -> refunds.recordDecision(context,
                booking.customerId(), booking.bookingId(), booking.terminalHistoryId())));
    }

    /** An ADMIN_* decision: the Platform Admin's audited service-loss assessment, then the refund that rests on it. */
    private UUID decideAsAdmin(UUID admin, Planted booking, boolean serviceLoss) {
        UUID correlationId = CorrelationId.require();
        return runner.run(new AdminCommand<>(admin, correlationId, booking.customerId(), context -> {
            context.jdbc().sql("""
                            SELECT loyalty.begin_admin_command(ARRAY[CAST(:user AS uuid)], :admin, :correlation)""")
                    .param("user", booking.customerId()).param("admin", admin).param("correlation", correlationId)
                    .query().singleRow();
            UUID assessment = context.jdbc().sql("SELECT loyalty.assess_service_loss(:booking, :history, :loss, :why)")
                    .param("booking", booking.bookingId()).param("history", booking.terminalHistoryId())
                    .param("loss", serviceLoss).param("why", "NFR-38 test assessment")
                    .query(UUID.class).single();
            return context.jdbc().sql("SELECT loyalty.record_refund(:booking, :history, :assessment)")
                    .param("booking", booking.bookingId()).param("history", booking.terminalHistoryId())
                    .param("assessment", assessment).query(UUID.class).single();
        }));
    }

    private static Refund refund(UUID refundId) {
        return VenueAnswerDb.OWNER.sql("""
                        SELECT treatment, terminal_reason, fee_snapshot, amount FROM loyalty.booking_fee_refund
                         WHERE id = :id""")
                .param("id", refundId)
                .query((rs, n) -> new Refund(rs.getString("treatment"), rs.getString("terminal_reason"),
                        rs.getBigDecimal("fee_snapshot"), rs.getBigDecimal("amount")))
                .single();
    }

    private static BigDecimal walletBalance(UUID userId) {
        return VenueAnswerDb.OWNER.sql("SELECT balance FROM loyalty.wallet_account WHERE user_id = :id")
                .param("id", userId).query(BigDecimal.class).single();
    }

    /** {@code loyalty.wallet_refunds_enabled} (A8), changed through its producer, as E7-68's admin route will. */
    private void walletSwitch(UUID admin, boolean on) {
        JdbcClient.create(adminDataSource).sql("""
                        SELECT loyalty.configure('loyalty.wallet_refunds_enabled', CAST(:value AS jsonb), :admin,
                                                 :request, 'E7-66 NFR-38 test')""")
                .param("value", Boolean.toString(on)).param("admin", admin).param("request", UUID.randomUUID())
                .query().singleRow();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** A category B command (an existing commitment's fee) under the given locks - the Customer's, at least. */
    private record Shared<R>(LockSet locks, Function<CommandContext, R> body) implements Command<R> {

        Shared(UUID customerId, Function<CommandContext, R> body) {
            this(LockSet.builder().users(customerId).build(), body);
        }

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(locks);
        }

        @Override
        public R execute(CommandContext context) {
            return body.apply(context);
        }
    }

    /** The same on the admin connection, through {@code admin.begin_command}. */
    private record AdminCommand<R>(UUID adminId, UUID correlationId, UUID customerId, Function<CommandContext, R> body)
            implements Command<R> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.admin(adminId, correlationId, LockSet.builder().users(customerId).build());
        }

        @Override
        public R execute(CommandContext context) {
            return body.apply(context);
        }
    }
}
