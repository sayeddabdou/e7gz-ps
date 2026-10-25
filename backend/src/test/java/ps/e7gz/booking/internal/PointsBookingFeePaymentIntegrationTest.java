package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.web.servlet.MockMvc;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.money.Money;
import ps.e7gz.common.persistence.DataSourceConfig;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.loyalty.api.BookingFeePayments.FeePayment;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.fixtures.Fixtures;

/**
 * PCR-010 (E7-68, Product Owner decisions 25 Oct 2026): Points pay the Booking Fee, and the fee is per booking or per
 * hour. Through the production commands - Online Booking creation, the venue's rejection and the customer's
 * cancellation - and the customer's reads: Points pay first (10,000 = one hour of fee, or one whole booking fee),
 * then the Wallet pays all of the rest or nothing; a refund gives the Points back; the quote shows the split before
 * booking and the booking and the Points history after it.
 *
 * <p>The fee settings are global (A8), so each test sets them through their producers and the launch values go back
 * afterwards; nothing else runs on the shared database meanwhile. A Wallet balance comes from a refund of a collected
 * fee, recorded the way the future payment gateway's adapter will (no runtime role may, v1.1.38).
 */
@AutoConfigureMockMvc
class PointsBookingFeePaymentIntegrationTest extends IntegrationTest {

    @Autowired
    CommandRunner runner;

    @Autowired
    OnlineBookingCreation creation;

    @Autowired
    CustomerBookings customerBookings;

    @Autowired
    VenueResponses responses;

    @Autowired
    BookingQuotes quotes;

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
    void setUp() {
        correlation = CorrelationId.open(CorrelationId.generate());
        world = OnlineBookingWorld.create();
        admin = Fixtures.anAdmin().create().id();
        walletSwitch(true);
    }

    @AfterEach
    void backToTheLaunchValues() {
        try {
            walletSwitch(false);
            config("booking.fee_mode", "\"PER_BOOKING\"");
            config("booking.fee", "0");
            config("booking.fee_per_hour", "0");
            pointsPerFeeUnit(10_000);
        } finally {
            correlation.close();
        }
    }

    /**
     * Done when: PER_HOUR at EGP 10, 10,000 Points per hour; 23,000 Points and EGP 3 in the Wallet; 2.5 hours. The fee
     * is EGP 25: 20,000 Points pay EGP 20 (4 half hours), the Wallet holds less than the EGP 5 left so it pays nothing,
     * and EGP 5 is left to pay. The venue then rejects the booking (a full refund): the 20,000 Points come back.
     */
    @Test
    void perHourPointsPayFourHalfHoursTheWalletPaysNothingAndARejectionGivesThePointsBack() throws Exception {
        UUID customer = world.customer.id();
        fundTheWallet(customer, "3.00");
        givePoints(customer, 23_000);
        config("booking.fee_mode", "\"PER_HOUR\"");
        config("booking.fee_per_hour", "10.00");

        // before booking: the quote shows the split it would make now
        BookingQuote quote = quotes.quote(customer, world.room.id(), PlayMode.SINGLE, 150);
        assertThat(quote.amounts().bookingFee()).isEqualTo(Money.of("25.00"));
        assertThat(quote.feePayment()).isEqualTo(new FeePayment(20_000, Money.of("20.00"), Money.ZERO,
                Money.of("5.00")));

        CreatedBooking booking = creation.create(world.request(customer, world.room,
                world.start().plus(Duration.ofHours(2)), 150));

        assertThat(booking.amounts().bookingFee()).isEqualTo(Money.of("25.00"));
        assertThat(booking.feePayment()).isEqualTo(new FeePayment(20_000, Money.of("20.00"), Money.ZERO,
                Money.of("5.00")));
        assertThat(pointsBalance(customer)).isEqualTo(3_000);
        assertThat(walletBalance(customer)).isEqualByComparingTo("3.00");
        assertThat(pointsPayment(booking.bookingId())).isEqualTo("PER_HOUR 10.00 25.00 10000 4 20000 20.00");
        assertThat(receipt(booking.bookingId())).as("the fee's money part stays uncollected").isNull();

        // after booking: the booking and the Points history show what was used, and what one unit pays
        String bearer = "Bearer " + accessTokens.issue(AuthSubjectType.CUSTOMER, customer);
        mvc.perform(get("/api/v1/customer/bookings/" + booking.bookingId()).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bookingFee.amount").value("25.00"))
                .andExpect(jsonPath("$.data.bookingFeePayment.pointsUsed").value(20_000))
                .andExpect(jsonPath("$.data.bookingFeePayment.paidByPoints.amount").value("20.00"))
                .andExpect(jsonPath("$.data.bookingFeePayment.paidByWallet.amount").value("0.00"))
                .andExpect(jsonPath("$.data.bookingFeePayment.leftToPay.amount").value("5.00"));

        responses.reject(world.account.id(), booking.bookingId(), "Room under maintenance");

        assertThat(pointsBalance(customer)).as("the Points used come back").isEqualTo(23_000);
        assertThat(pointsRows(customer)).containsExactly("ADMIN 23000", "BOOKING_FEE_PAYMENT -20000",
                "BOOKING_FEE_REFUND 20000");
        assertThat(walletBalance(customer)).as("no money was collected, so none comes back")
                .isEqualByComparingTo("3.00");
        mvc.perform(get("/api/v1/customer/points").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(23_000))
                .andExpect(jsonPath("$.data.feeUnit.bookingFeeMode").value("PER_HOUR"))
                .andExpect(jsonPath("$.data.feeUnit.pointsPerFeeUnit").value(10_000))
                .andExpect(jsonPath("$.data.items[0].cause").value("BOOKING_FEE_REFUND"))
                .andExpect(jsonPath("$.data.items[0].amount").value(20_000))
                .andExpect(jsonPath("$.data.items[0].bookingId").value(booking.bookingId().toString()))
                .andExpect(jsonPath("$.data.items[1].cause").value("BOOKING_FEE_PAYMENT"))
                .andExpect(jsonPath("$.data.items[1].amount").value(-20_000))
                .andExpect(jsonPath("$.data.items[1].bookingId").value(booking.bookingId().toString()));
    }

    /** Done when: PER_BOOKING at EGP 20 - 12,000 Points pay the whole fee with 10,000; 7,000 Points pay nothing. */
    @Test
    void perBookingAFullUnitPaysTheWholeFeeAndFewerPointsPayNothing() {
        config("booking.fee", "20.00");
        UUID rich = world.customer.id();
        UUID poor = world.anotherCustomer();
        givePoints(rich, 12_000);
        givePoints(poor, 7_000);

        CreatedBooking paid = creation.create(world.request(rich, world.room, world.start(), 60));
        CreatedBooking unpaid = creation.create(world.request(poor, world.room,
                world.start().plus(Duration.ofHours(2)), 60));

        assertThat(paid.feePayment()).isEqualTo(new FeePayment(10_000, Money.of("20.00"), Money.ZERO, Money.ZERO));
        assertThat(pointsBalance(rich)).isEqualTo(2_000);
        assertThat(pointsPayment(paid.bookingId())).isEqualTo("PER_BOOKING 20.00 20.00 10000 null 10000 20.00");
        assertThat(unpaid.feePayment()).isEqualTo(FeePayment.unpaid(Money.of("20.00")));
        assertThat(pointsBalance(poor)).isEqualTo(7_000);
        assertThat(pointsPayment(unpaid.bookingId())).isNull();
    }

    /**
     * PCR-010 item 5: Points pay what they cover and the Wallet pays all of the rest when it holds it; a refund within
     * 15 minutes gives back both - the Points to the Points balance, the money to the Wallet (FR-PTS-14).
     */
    @Test
    void theWalletPaysWhatPointsLeaveAndARefundGivesBackBoth() {
        UUID customer = world.customer.id();
        fundTheWallet(customer, "6.00");
        givePoints(customer, 5_000);
        config("booking.fee_mode", "\"PER_HOUR\"");
        config("booking.fee_per_hour", "10.00");

        CreatedBooking booking = creation.create(world.request(customer, world.room,
                world.start().plus(Duration.ofHours(2)), 60));

        assertThat(booking.feePayment()).as("5,000 Points pay a half hour; the Wallet pays the other EGP 5")
                .isEqualTo(new FeePayment(5_000, Money.of("5.00"), Money.of("5.00"), Money.ZERO));
        assertThat(pointsBalance(customer)).isZero();
        assertThat(walletBalance(customer)).isEqualByComparingTo("1.00");
        assertThat(receipt(booking.bookingId())).isEqualTo("WALLET 5.00");

        customerBookings.cancel(customer, booking.bookingId());

        assertThat(pointsBalance(customer)).isEqualTo(5_000);
        assertThat(walletBalance(customer)).isEqualByComparingTo("6.00");
        assertThat(walletRows(customer)).endsWith("BOOKING_FEE_PAYMENT -5.00", "BOOKING_FEE_REFUND 5.00");
    }

    /**
     * E7-68 done when: changing the booking fee affects only bookings created after the change (FR-ADM-11, FR-AUD-01) -
     * changed through PUT /admin/fees, first to EGP 5 per booking, then to EGP 10 per hour.
     */
    @Test
    void aFeeChangeAffectsOnlyBookingsCreatedAfterIt() throws Exception {
        CreatedBooking before = creation.create(world.request(world.customer.id(), world.room, world.start(), 90));
        String adminBearer = "Bearer " + accessTokens.issue(AuthSubjectType.ADMIN, admin);

        changeFees(adminBearer, "{\"bookingFee\":{\"amount\":\"5.00\",\"currency\":\"EGP\"},\"reason\":\"On.\"}");
        CreatedBooking perBooking = creation.create(world.request(world.anotherCustomer(), world.room,
                world.start().plus(Duration.ofHours(2)), 90));
        changeFees(adminBearer, "{\"bookingFeeMode\":\"PER_HOUR\",\"bookingFeePerHour\":{\"amount\":\"10.00\","
                + "\"currency\":\"EGP\"},\"reason\":\"Hourly.\"}");
        CreatedBooking perHour = creation.create(world.request(world.anotherCustomer(), world.room,
                world.start().plus(Duration.ofHours(4)), 90));

        assertThat(storedFee(before.bookingId())).isEqualByComparingTo("0.00");
        assertThat(storedFee(perBooking.bookingId())).isEqualByComparingTo("5.00");
        assertThat(storedFee(perHour.bookingId())).as("1.5 h at EGP 10, by the half hour").isEqualByComparingTo("15.00");
    }

    /** At the launch values nothing changes: EGP 0, FREE, no Points or Wallet movement, an all-zero split. */
    @Test
    void atTheLaunchFeeNothingIsPaid() throws Exception {
        UUID customer = world.customer.id();
        givePoints(customer, 50_000);

        BookingQuote quote = quotes.quote(customer, world.room.id(), PlayMode.SINGLE, 120);
        CreatedBooking booking = creation.create(world.request(customer, world.room, world.start(), 120));

        assertThat(quote.feePayment()).isEqualTo(FeePayment.unpaid(Money.ZERO));
        assertThat(booking.feePayment()).isEqualTo(FeePayment.unpaid(Money.ZERO));
        assertThat(pointsBalance(customer)).isEqualTo(50_000);
        mvc.perform(get("/api/v1/customer/bookings/quote").header("Authorization",
                                "Bearer " + accessTokens.issue(AuthSubjectType.CUSTOMER, customer))
                        .param("resourceId", world.room.id().toString()).param("playMode", "SINGLE")
                        .param("startAt", world.start().toString()).param("durationMinutes", "120"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bookingFee.displayAsFree").value(true))
                .andExpect(jsonPath("$.data.bookingFeePayment.pointsUsed").value(0))
                .andExpect(jsonPath("$.data.bookingFeePayment.leftToPay.amount").value("0.00"));
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    /**
     * A collected fee of {@code amount} that the customer then cancels within 15 minutes: its refund credits the
     * Wallet with {@code amount}. Needs a customer without a full unit of Points, so the Points pay none of it.
     */
    private void fundTheWallet(UUID customer, String amount) {
        config("booking.fee", amount);
        CreatedBooking paid = creation.create(world.request(customer, world.room, world.start(), 60));
        recordReceiptAsTheSettlementAdapter(paid, amount);
        customerBookings.cancel(customer, paid.bookingId());
        config("booking.fee", "0");
    }

    private static void recordReceiptAsTheSettlementAdapter(CreatedBooking booking, String amount) {
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
                    .param("booking", booking.bookingId()).param("amount", amount)
                    .param("reference", "pcr010-" + booking.bookingId())
                    .param("providerAt", OffsetDateTime.ofInstant(booking.createdAt(), ZoneOffset.UTC))
                    .query().singleRow();
            owner.getConnection().commit();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        } finally {
            owner.destroy();
        }
    }

    /** Points through the Platform Admin's adjustment producer (FR-PTS-11), as POST /admin/points/adjust does. */
    private void givePoints(UUID customer, int points) {
        UUID correlationId = CorrelationId.require();
        runner.run(new AdminCommand(admin, correlationId, LockSet.builder().users(customer).build(), context -> {
            context.jdbc().sql("SELECT loyalty.begin_admin_command(ARRAY[CAST(:user AS uuid)], :admin, :correlation)")
                    .param("user", customer).param("admin", admin).param("correlation", correlationId)
                    .query().singleRow();
            context.jdbc().sql("SELECT loyalty.adjust_points(:user, :amount, :request, 'PCR-010 test')")
                    .param("user", customer).param("amount", points).param("request", UUID.randomUUID())
                    .query().singleRow();
        }));
    }

    /** A Booking Fee key (A8, PCR-010) through its producer, skipped when unchanged (the producer refuses that). */
    private void config(String key, String json) {
        runner.run(new AdminCommand(admin, CorrelationId.require(), LockSet.empty(), context -> {
            String current = context.jdbc().sql("""
                            SELECT c.new_value::text FROM config.configuration_version c WHERE c.key = :key
                             ORDER BY c.effective_from DESC, c.version_position DESC LIMIT 1""")
                    .param("key", key).query(String.class).single();
            boolean same = current.startsWith("\"") ? current.equals(json)
                    : new BigDecimal(current).compareTo(new BigDecimal(json)) == 0;
            if (!same) {
                context.jdbc().sql("SELECT config.change_configuration(:key, CAST(:value AS jsonb), 'PCR-010 test')")
                        .param("key", key).param("value", json).query().singleRow();
            }
        }));
    }

    private void pointsPerFeeUnit(int unit) {
        Integer current = JdbcClient.create(adminDataSource)
                .sql("SELECT (value #>> '{}')::integer FROM config.system_configuration WHERE key = 'loyalty.points_per_fee_unit'")
                .query(Integer.class).single();
        if (current != unit) {
            configure("loyalty.points_per_fee_unit", Integer.toString(unit));
        }
    }

    private void walletSwitch(boolean on) {
        configure("loyalty.wallet_refunds_enabled", Boolean.toString(on));
    }

    private void configure(String key, String value) {
        JdbcClient.create(adminDataSource).sql("""
                        SELECT loyalty.configure(:key, CAST(:value AS jsonb), :admin, :request, 'PCR-010 test')""")
                .param("key", key).param("value", value).param("admin", admin).param("request", UUID.randomUUID())
                .query().singleRow();
    }

    private void changeFees(String adminBearer, String body) throws Exception {
        mvc.perform(put("/api/v1/admin/fees").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", adminBearer)
                        .header(OpenApiConfig.IDEMPOTENCY_KEY, UUID.randomUUID().toString()).content(body))
                .andExpect(status().isOk());
    }

    private static BigDecimal storedFee(UUID bookingId) {
        return VenueAnswerDb.OWNER.sql("SELECT booking_fee_snapshot FROM booking.booking WHERE id = :id")
                .param("id", bookingId).query(BigDecimal.class).single();
    }

    private static int pointsBalance(UUID customer) {
        return VenueAnswerDb.OWNER.sql("SELECT balance FROM loyalty.points_account WHERE user_id = :id")
                .param("id", customer).query(Integer.class).single();
    }

    private static BigDecimal walletBalance(UUID customer) {
        return VenueAnswerDb.OWNER.sql("SELECT balance FROM loyalty.wallet_account WHERE user_id = :id")
                .param("id", customer).query(BigDecimal.class).single();
    }

    private static List<String> pointsRows(UUID customer) {
        return VenueAnswerDb.OWNER.sql("""
                        SELECT cause || ' ' || amount FROM loyalty.points_transaction
                         WHERE points_account_id = :id ORDER BY ledger_sequence""")
                .param("id", customer).query(String.class).list();
    }

    private static List<String> walletRows(UUID customer) {
        return VenueAnswerDb.OWNER.sql("""
                        SELECT cause || ' ' || amount FROM loyalty.wallet_transaction
                         WHERE wallet_account_id = :id ORDER BY ledger_sequence""")
                .param("id", customer).query(String.class).list();
    }

    /** {@code mode rate fee unit halfHours points paid} of the booking's Points payment, or null without one. */
    private static String pointsPayment(UUID bookingId) {
        return VenueAnswerDb.OWNER.sql("""
                        SELECT fee_mode || ' ' || fee_rate || ' ' || fee_amount || ' ' || points_per_fee_unit || ' '
                               || COALESCE(half_hours_paid::text, 'null') || ' ' || points_used || ' ' || amount_paid
                          FROM loyalty.booking_fee_points_payment WHERE booking_id = :id""")
                .param("id", bookingId).query(String.class).list().stream().findFirst().orElse(null);
    }

    /** {@code provider amount} of the booking's collection receipt, or null when the fee's money was not collected. */
    private static String receipt(UUID bookingId) {
        return VenueAnswerDb.OWNER.sql("""
                        SELECT provider || ' ' || amount FROM loyalty.booking_fee_receipt WHERE booking_id = :id""")
                .param("id", bookingId).query(String.class).list().stream().findFirst().orElse(null);
    }

    /** A Platform Admin command; category A, as every admin command that names no Venue (ADR-022). */
    private record AdminCommand(UUID admin, UUID correlationId, LockSet locks,
                                java.util.function.Consumer<CommandContext> body) implements Command<Void> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.A_NEW_ACTIVITY_MARKETPLACE;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.admin(admin, correlationId, locks);
        }

        @Override
        public Void execute(CommandContext context) {
            body.accept(context);
            return null;
        }
    }
}
