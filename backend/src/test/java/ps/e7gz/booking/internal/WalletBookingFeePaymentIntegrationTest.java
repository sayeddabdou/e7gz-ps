package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.web.servlet.MockMvc;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.persistence.DataSourceConfig;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.loyalty.api.BookingFeePayments;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.fixtures.Fixtures;

/**
 * PCR-009 (E7-66 PR 3, Product Owner decision 24 Oct 2026): the Wallet pays a future Booking's Booking Fee. With a
 * {@value #FEE} fee, through the production commands - Online Booking creation and the customer's cancellation:
 * a refund credits the Wallet, never the card; the next booking's whole fee is paid from the Wallet; a refund of that
 * fee goes back to the Wallet (FR-PTS-14: within 15 minutes of creation), a late cancellation keeps it; a Wallet that
 * does not cover the fee pays nothing and the fee stays uncollected, as before (ADR-018).
 *
 * <p>The fee is a global setting ({@code booking.fee}, A8), so this test sets it through its producer for its own run
 * and puts EGP 0 back afterwards; nothing else runs on the shared database meanwhile. The first refund needs a
 * collected fee, recorded the way the future payment gateway's adapter will (no runtime role may, v1.1.38).
 */
@AutoConfigureMockMvc
class WalletBookingFeePaymentIntegrationTest extends IntegrationTest {

    static final String FEE = "5.00";

    @Autowired
    CommandRunner runner;

    @Autowired
    OnlineBookingCreation creation;

    @Autowired
    CustomerBookings customerBookings;

    @Autowired
    BookingFeePayments payments;

    @Autowired
    MockMvc mvc;

    @Autowired
    AccessTokens accessTokens;

    @Autowired
    @Qualifier(DataSourceConfig.ADMIN_DATA_SOURCE)
    DataSource adminDataSource;

    private CorrelationId.Scope correlation;
    private OnlineBookingWorld world;
    private UUID admin;

    @BeforeEach
    void aNonZeroFee() {
        correlation = CorrelationId.open(CorrelationId.generate());
        world = OnlineBookingWorld.create();
        admin = Fixtures.anAdmin().create().id();
        bookingFee(FEE);
        walletSwitch(true);
    }

    @AfterEach
    void backToTheLaunchFee() {
        try {
            walletSwitch(false);
            bookingFee("0");
        } finally {
            correlation.close();
        }
    }

    @Test
    void aRefundedFeePaysTheNextBookingAndARefundOfThatFeeGoesBackToTheWallet() throws Exception {
        UUID customer = world.customer.id();
        fundTheWalletWithARefund(customer, world.start());
        assertThat(walletBalance(customer)).isEqualByComparingTo(FEE);

        // the next booking: the Wallet pays its whole fee, automatically
        CreatedBooking next = creation.create(world.request(customer, world.room,
                world.start().plus(Duration.ofHours(2)), 60));
        assertThat(next.amounts().bookingFee().toString()).isEqualTo(FEE);
        assertThat(walletBalance(customer)).isEqualByComparingTo("0.00");
        assertThat(receipt(next.bookingId())).isEqualTo("WALLET " + FEE);
        assertThat(walletRows(customer)).containsExactly("BOOKING_FEE_REFUND 5.00", "BOOKING_FEE_PAYMENT -5.00");

        // the customer's wallet shows the payment as what moved, never a negative amount
        mvc.perform(get("/api/v1/customer/wallet").header("Authorization",
                        "Bearer " + accessTokens.issue(AuthSubjectType.CUSTOMER, customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance.amount").value("0.00"))
                .andExpect(jsonPath("$.data.items[0].cause").value("BOOKING_FEE_PAYMENT"))
                .andExpect(jsonPath("$.data.items[0].amount.amount").value(FEE))
                .andExpect(jsonPath("$.data.items[0].balanceAfter.amount").value("0.00"))
                .andExpect(jsonPath("$.data.items[0].bookingId").value(next.bookingId().toString()))
                .andExpect(jsonPath("$.data.items[1].cause").value("BOOKING_FEE_REFUND"));

        // cancelled within 15 minutes of creation (FR-PTS-14): the fee goes back to the Wallet, not the card
        customerBookings.cancel(customer, next.bookingId());
        assertThat(walletBalance(customer)).isEqualByComparingTo(FEE);
        assertThat(walletRows(customer)).containsExactly("BOOKING_FEE_REFUND 5.00", "BOOKING_FEE_PAYMENT -5.00",
                "BOOKING_FEE_REFUND 5.00");
    }

    @Test
    void aLateCancellationOfAWalletPaidBookingKeepsTheFee() {
        UUID customer = world.customer.id();
        fundTheWalletWithARefund(customer, world.start());
        CreatedBooking next = creation.create(world.request(customer, world.room,
                world.start().plus(Duration.ofHours(2)), 60));
        assertThat(walletBalance(customer)).isEqualByComparingTo("0.00");
        // made 20 minutes ago: past the 15-minute customer policy (FR-PTS-14)
        VenueAnswerDb.withoutTriggers(jdbc -> {
            jdbc.sql("UPDATE booking.booking SET created_at = created_at - interval '20 minutes' WHERE id = :id")
                    .param("id", next.bookingId()).update();
            jdbc.sql("""
                            UPDATE booking.booking_history SET occurred_at = occurred_at - interval '20 minutes'
                             WHERE booking_id = :id AND sequence = 1""")
                    .param("id", next.bookingId()).update();
        });

        customerBookings.cancel(customer, next.bookingId());

        assertThat(VenueAnswerDb.OWNER.sql("SELECT amount FROM loyalty.booking_fee_refund WHERE booking_id = :id")
                .param("id", next.bookingId()).query(BigDecimal.class).single()).isEqualByComparingTo("0.00");
        assertThat(walletBalance(customer)).as("E7GZ keeps the fee").isEqualByComparingTo("0.00");
    }

    @Test
    void aWalletThatDoesNotCoverTheWholeFeePaysNothing() {
        UUID customer = world.anotherCustomer();

        CreatedBooking booking = creation.create(world.request(customer, world.room, world.start(), 60));

        assertThat(booking.amounts().bookingFee().toString()).isEqualTo(FEE);
        assertThat(receipt(booking.bookingId())).as("the fee stays uncollected, as before").isNull();
        assertThat(walletRows(customer)).isEmpty();
    }

    @Test
    void onlyTheCommandThatCreatesTheBookingPaysAndASecondCallAnswersTheSamePayment() {
        UUID customer = world.customer.id();
        fundTheWalletWithARefund(customer, world.start());
        CreatedBooking next = creation.create(world.request(customer, world.room,
                world.start().plus(Duration.ofHours(2)), 60));
        UUID paid = VenueAnswerDb.OWNER.sql("""
                        SELECT id FROM loyalty.wallet_transaction
                         WHERE booking_id = :id AND cause = 'BOOKING_FEE_PAYMENT'""")
                .param("id", next.bookingId()).query(UUID.class).single();

        Optional<UUID> again = runner.run(new Shared<Optional<UUID>>(customer, context ->
                payments.payFromWallet(context, customer, next.bookingId())));
        assertThat(again).contains(paid);

        CreatedBooking unpaid = creation.create(world.request(world.anotherCustomer(), world.room,
                world.start().plus(Duration.ofHours(4)), 60));
        UUID other = VenueAnswerDb.OWNER.sql("SELECT customer_id FROM booking.booking WHERE id = :id")
                .param("id", unpaid.bookingId()).query(UUID.class).single();
        assertThatThrownBy(() -> runner.run(new Shared<>(other, context ->
                payments.payFromWallet(context, other, unpaid.bookingId()))))
                .rootCause().hasMessageContaining("only by the command that creates the Booking");
    }

    @Test
    void aFreeBookingNeverTouchesTheWallet() {
        bookingFee("0");
        UUID customer = world.anotherCustomer();

        CreatedBooking booking = creation.create(world.request(customer, world.room, world.start(), 60));

        assertThat(booking.amounts().bookingFee().isZero()).isTrue();
        assertThat(receipt(booking.bookingId())).isNull();
        assertThat(walletRows(customer)).isEmpty();
        bookingFee(FEE);
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    /**
     * A booking whose fee was collected (by the future adapter) and that the customer then cancels within 15 minutes:
     * its refund credits the Wallet with {@value #FEE}.
     */
    private void fundTheWalletWithARefund(UUID customer, Instant startAt) {
        CreatedBooking paid = creation.create(world.request(customer, world.room, startAt, 60));
        recordReceiptAsTheSettlementAdapter(paid);
        customerBookings.cancel(customer, paid.bookingId());
    }

    private static void recordReceiptAsTheSettlementAdapter(CreatedBooking booking) {
        SingleConnectionDataSource owner = new SingleConnectionDataSource(
                PostgresTestContainer.jdbcUrl(PostgresTestContainer.DATABASE), PostgresTestContainer.MIGRATOR,
                PostgresTestContainer.MIGRATOR, true);
        owner.setAutoCommit(false);
        try {
            JdbcClient jdbc = JdbcClient.create(owner);
            jdbc.sql("SELECT loyalty.begin_command(ARRAY[CAST(:user AS uuid)], :correlation)")
                    .param("user", booking.customerId()).param("correlation", CorrelationId.require())
                    .query().singleRow();
            jdbc.sql("""
                            SELECT loyalty.record_fee_receipt(:booking, CAST(:amount AS numeric), 'TEST_ADAPTER',
                                                              :reference, :providerAt)""")
                    .param("booking", booking.bookingId()).param("amount", FEE)
                    .param("reference", "pcr009-" + booking.bookingId())
                    .param("providerAt", OffsetDateTime.ofInstant(booking.createdAt(), ZoneOffset.UTC))
                    .query().singleRow();
            owner.getConnection().commit();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        } finally {
            owner.destroy();
        }
    }

    private static BigDecimal walletBalance(UUID customer) {
        return VenueAnswerDb.OWNER.sql("SELECT balance FROM loyalty.wallet_account WHERE user_id = :id")
                .param("id", customer).query(BigDecimal.class).single();
    }

    private static List<String> walletRows(UUID customer) {
        return VenueAnswerDb.OWNER.sql("""
                        SELECT cause || ' ' || amount FROM loyalty.wallet_transaction
                         WHERE wallet_account_id = :id ORDER BY ledger_sequence""")
                .param("id", customer).query(String.class).list();
    }

    /** {@code provider amount} of the booking's collection receipt, or null when the fee was not collected. */
    private static String receipt(UUID bookingId) {
        return VenueAnswerDb.OWNER.sql("""
                        SELECT provider || ' ' || amount FROM loyalty.booking_fee_receipt WHERE booking_id = :id""")
                .param("id", bookingId).query(String.class).list().stream().findFirst().orElse(null);
    }

    /** {@code booking.fee} (A8) through its producer, as E7-68's admin route will change it. */
    private void bookingFee(String amount) {
        UUID correlationId = CorrelationId.require();
        runner.run(new Command<Void>() {
            @Override
            public EligibilityCategory eligibilityCategory() {
                return EligibilityCategory.A_NEW_ACTIVITY_MARKETPLACE;
            }

            @Override
            public CommandScope scope() {
                return CommandScope.admin(admin, correlationId, LockSet.empty());
            }

            @Override
            public Void execute(CommandContext context) {
                BigDecimal current = context.jdbc()
                        .sql("SELECT config.effective_numeric('booking.fee', clock_timestamp())")
                        .query(BigDecimal.class).single();
                // change_configuration refuses an unchanged value
                if (current.compareTo(new BigDecimal(amount)) != 0) {
                    context.jdbc().sql("SELECT config.change_configuration('booking.fee', CAST(:value AS jsonb), :why)")
                            .param("value", amount).param("why", "PCR-009 test").query().singleRow();
                }
                return null;
            }
        });
    }

    private void walletSwitch(boolean on) {
        JdbcClient.create(adminDataSource).sql("""
                        SELECT loyalty.configure('loyalty.wallet_refunds_enabled', CAST(:value AS jsonb), :admin,
                                                 :request, 'PCR-009 test')""")
                .param("value", Boolean.toString(on)).param("admin", admin).param("request", UUID.randomUUID())
                .query().singleRow();
    }

    /** A category B command under the Customer's lock. */
    private record Shared<R>(UUID customerId, Function<CommandContext, R> body) implements Command<R> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.builder().users(customerId).build());
        }

        @Override
        public R execute(CommandContext context) {
            return body.apply(context);
        }
    }
}
