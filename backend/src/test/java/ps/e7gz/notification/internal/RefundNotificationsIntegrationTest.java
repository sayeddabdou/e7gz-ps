package ps.e7gz.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.web.servlet.MockMvc;
import ps.e7gz.booking.internal.BookingFlows;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.OutboxEvent;
import ps.e7gz.notification.api.NotifiedBookings;
import ps.e7gz.notification.api.RefundedBookings;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;

/**
 * E7-80 (PO decisions 25 Nov 2026, Q6, R1, R4, R5): the refund confirmation for a booking a real Emergency cascade
 * cancelled (its own {@code booking_fee_refund} decision), from ledger events shaped as the loyalty producers write
 * them - a Booking Fee above EGP 0 cannot be collected at launch (PO decision 15 Nov 2026), so the credits are
 * delivered by hand. Each event is handled twice, as a dispatcher retry would.
 */
@AutoConfigureMockMvc
class RefundNotificationsIntegrationTest extends IntegrationTest {

    private static final JdbcClient OWNER = JdbcClient.create(new SingleConnectionDataSource(
            PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR, true));

    @Autowired
    MockMvc mvc;

    @Autowired
    AccessTokens accessTokens;

    @Autowired
    ApplicationContext context;

    @Autowired
    DataSource appDataSource;

    @Autowired
    ObjectMapper json;

    @Autowired
    CommandRunner runner;

    @Autowired
    NotificationService notifications;

    @Autowired
    NotifiedBookings notifiedBookings;

    @Autowired
    RefundedBookings refundedBookings;

    JdbcClient db;
    BookingFlows flows;
    RefundNotificationConsumer consumer;
    CorrelationId.Scope correlation;
    long nextEventId = 9_000_000_000L;

    @BeforeEach
    void setUp() {
        correlation = CorrelationId.open(CorrelationId.generate());
        db = JdbcClient.create(appDataSource);
        flows = BookingFlows.of(context);
        consumer = new RefundNotificationConsumer(runner, notifications, notifiedBookings, refundedBookings);
    }

    @AfterEach
    void tearDown() {
        correlation.close();
    }

    @Test
    void emergencyCancelWalletPaidFee() throws Exception {
        BookingFlows.Booking booking = cancelledByAnEmergency();

        handleTwice(walletCredit(booking, "25.00"));

        assertThat(confirmations(booking)).containsExactly("OPERATIONAL_REFUND_CONFIRMED WALLET 25.00");
    }

    @Test
    void emergencyCancelPointsPaidFee() throws Exception {
        BookingFlows.Booking booking = cancelledByAnEmergency();
        UUID transaction = pointsRefundDecision(booking);

        handleTwice(pointsRefund(booking, transaction, 10_000));

        assertThat(confirmations(booking)).containsExactly("OPERATIONAL_REFUND_CONFIRMED POINTS 10000");
    }

    @Test
    void splitFeeGivesTwoMessages() throws Exception {
        BookingFlows.Booking booking = cancelledByAnEmergency();
        UUID transaction = pointsRefundDecision(booking);

        handleTwice(pointsRefund(booking, transaction, 5_000));
        handleTwice(walletCredit(booking, "5.00"));

        assertThat(confirmations(booking)).containsExactlyInAnyOrder("OPERATIONAL_REFUND_CONFIRMED POINTS 5000",
                "OPERATIONAL_REFUND_CONFIRMED WALLET 5.00");
    }

    @Test
    void anOrdinaryCancellationGivesTheFeeRefundCode() throws Exception {
        BookingFlows.Booking booking = flows.pending();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/customer/bookings/" + booking.bookingId() + "/cancel")
                        .header("Authorization", "Bearer " + accessTokens.issue(
                                ps.e7gz.common.codes.AuthSubjectType.CUSTOMER, booking.customerId()))
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

        handleTwice(walletCredit(booking, "25.00"));

        assertThat(confirmations(booking)).containsExactly("BOOKING_FEE_REFUND_CONFIRMED WALLET 25.00");
    }

    @Test
    void aWalletPaymentOrAZeroCreditTellsNobody() throws Exception {
        BookingFlows.Booking booking = cancelledByAnEmergency();
        Map<String, Object> payment = new LinkedHashMap<>();
        payment.put("walletTransactionId", UUID.randomUUID().toString());
        payment.put("receiptId", UUID.randomUUID().toString());
        payment.put("bookingId", booking.bookingId().toString());
        payment.put("amount", -25.00);

        handleTwice(event(IntegrationEvents.LOYALTY_WALLET_LEDGER_COMMITTED, booking, payment));
        handleTwice(walletCredit(booking, "0.00"));

        assertThat(confirmations(booking)).isEmpty();
    }

    // ------------------------------------------------------------------------------------------------ helpers

    BookingFlows.Booking cancelledByAnEmergency() throws Exception {
        BookingFlows.Booking booking = flows.pending();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/venue/emergencies/venue-closure")
                        .header("Authorization", "Bearer " + accessTokens.issue(
                                ps.e7gz.common.codes.AuthSubjectType.VENUE, booking.venueAccountId()))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Power cut\",\"expectedEndAt\":\""
                                + booking.startAt().plus(Duration.ofMinutes(60)) + "\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        return booking;
    }

    void handleTwice(OutboxEvent event) {
        consumer.handle(event);
        consumer.handle(event);
    }

    OutboxEvent walletCredit(BookingFlows.Booking booking, String amount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("walletTransactionId", UUID.randomUUID().toString());
        payload.put("refundId", UUID.randomUUID().toString());
        payload.put("receiptId", UUID.randomUUID().toString());
        payload.put("bookingId", booking.bookingId().toString());
        payload.put("amount", new java.math.BigDecimal(amount));
        return event(IntegrationEvents.LOYALTY_WALLET_LEDGER_COMMITTED, booking, payload);
    }

    OutboxEvent pointsRefund(BookingFlows.Booking booking, UUID transaction, int points) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transactionId", transaction.toString());
        payload.put("cause", "BOOKING_FEE_REFUND");
        payload.put("bookingId", null);
        payload.put("appliedAmount", points);
        return event(IntegrationEvents.LOYALTY_POINTS_LEDGER_COMMITTED, booking, payload);
    }

    OutboxEvent event(String type, BookingFlows.Booking booking, Map<String, Object> payload) {
        payload.put("recipientUserId", booking.customerId().toString());
        payload.put("occurredAt", Instant.now().toString());
        return new OutboxEvent(nextEventId++, type, "LOYALTY", UUID.randomUUID(), json.valueToTree(payload),
                UUID.randomUUID(), Instant.EPOCH, 0);
    }

    /** The cascade's refund decision, as if Points had paid the fee: it names the Points transaction it gave back. */
    UUID pointsRefundDecision(BookingFlows.Booking booking) {
        UUID transaction = UUID.randomUUID();
        // ck_fee_refund_points: a decision that gave Points back refunds an amount above 0
        behindTheGuards("UPDATE loyalty.booking_fee_refund SET points_transaction_id = '" + transaction
                + "', fee_snapshot = 5.00, amount = 5.00 WHERE booking_id = '" + booking.bookingId() + "'");
        return transaction;
    }

    static void behindTheGuards(String sql) {
        SingleConnectionDataSource owner = new SingleConnectionDataSource(PostgresTestContainer.jdbcUrl(DATABASE),
                MIGRATOR, MIGRATOR, true);
        try {
            JdbcClient jdbc = JdbcClient.create(owner);
            jdbc.sql("SET session_replication_role = replica").update();
            assertThat(jdbc.sql(sql).update()).as(sql).isOne();
            jdbc.sql("SET session_replication_role = origin").update();
        } finally {
            owner.destroy();
        }
    }

    List<String> confirmations(BookingFlows.Booking booking) {
        return db.sql("""
                        SELECT code || ' ' || (params ->> 'refundedAs') || ' ' || (params ->> 'amount')
                          FROM notification.notification
                         WHERE recipient_id = :customer AND booking_id = :booking
                           AND code IN ('OPERATIONAL_REFUND_CONFIRMED', 'BOOKING_FEE_REFUND_CONFIRMED')""")
                .param("customer", booking.customerId()).param("booking", booking.bookingId())
                .query(String.class).list();
    }
}
