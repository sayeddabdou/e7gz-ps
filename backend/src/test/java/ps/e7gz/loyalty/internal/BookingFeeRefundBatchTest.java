package ps.e7gz.loyalty.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.jdbc.core.simple.JdbcClient;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.loyalty.api.BookingFeeRefunds.Decision;
import ps.e7gz.support.boundary.CommandInstants;

/**
 * E7-113 - the Loyalty refund batch without a database: the whole batch is checked before any SQL, the Loyalty
 * context is opened once with every Customer, sorted and distinct, and one {@code loyalty.record_refund} follows per
 * booking in Customer / booking order. {@code BookingFeeRefundBatchIntegrationTest} runs the same batch on PostgreSQL.
 */
class BookingFeeRefundBatchTest {

    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");

    // Fixed ids, so the expected order is visible: customer A < customer B, booking 1 < 2 < 3.
    private static final UUID CUSTOMER_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID CUSTOMER_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID BOOKING_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOOKING_2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID BOOKING_3 = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID HISTORY_1 = UUID.randomUUID();
    private static final UUID HISTORY_2 = UUID.randomUUID();
    private static final UUID HISTORY_3 = UUID.randomUUID();

    private final List<Call> calls = new ArrayList<>();
    private final Map<UUID, UUID> refundIds = new LinkedHashMap<>();
    private final JdbcBookingFeeRefunds refunds = new JdbcBookingFeeRefunds();
    private CommandContext command;
    private CorrelationId.Scope correlation;
    private UUID correlationId;

    /** One statement as the fake JdbcClient saw it: its SQL and its named parameters. */
    record Call(String sql, Map<String, Object> params) {
    }

    @BeforeEach
    void setUp() {
        correlationId = CorrelationId.generate();
        correlation = CorrelationId.open(correlationId);
        command = CommandInstants.at(NOW, recordingJdbc());
    }

    @AfterEach
    void tearDown() {
        correlation.close();
    }

    @Test
    void anEmptyBatchRunsNoSql() {
        Map<UUID, UUID> result = refunds.recordDecisions(command, List.of());

        assertThat(result).isEmpty();
        assertThat(calls).isEmpty();
    }

    @Test
    void aNullListOrANullDecisionIsRefusedBeforeAnySql() {
        assertThatThrownBy(() -> refunds.recordDecisions(command, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> refunds.recordDecisions(command,
                Arrays.asList(new Decision(CUSTOMER_A, BOOKING_1, HISTORY_1), null)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> refunds.recordDecisions(null, List.of(new Decision(CUSTOMER_A, BOOKING_1, HISTORY_1))))
                .isInstanceOf(NullPointerException.class);
        assertThat(calls).isEmpty();
    }

    @Test
    void aDecisionWithANullMemberCannotBeBuilt() {
        assertThatThrownBy(() -> new Decision(null, BOOKING_1, HISTORY_1)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("customerId");
        assertThatThrownBy(() -> new Decision(CUSTOMER_A, null, HISTORY_1)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("bookingId");
        assertThatThrownBy(() -> new Decision(CUSTOMER_A, BOOKING_1, null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("terminalHistoryId");
    }

    @Test
    void aBookingTwiceIsRefusedBeforeAnySql() {
        List<Decision> twice = List.of(new Decision(CUSTOMER_A, BOOKING_1, HISTORY_1),
                new Decision(CUSTOMER_B, BOOKING_2, HISTORY_2), new Decision(CUSTOMER_A, BOOKING_1, HISTORY_3));

        assertThatThrownBy(() -> refunds.recordDecisions(command, twice))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(BOOKING_1.toString());
        assertThat(calls).isEmpty();
    }

    @Test
    void twoCustomersThreeBookingsOpenOneContextAndRecordEachBookingOnceInOrder() {
        // Given out of order on purpose: B's booking first, then A's two bookings reversed.
        List<Decision> decisions = List.of(new Decision(CUSTOMER_B, BOOKING_1, HISTORY_1),
                new Decision(CUSTOMER_A, BOOKING_3, HISTORY_3), new Decision(CUSTOMER_A, BOOKING_2, HISTORY_2));

        Map<UUID, UUID> result = refunds.recordDecisions(command, decisions);

        assertThat(calls).hasSize(4);
        Call begin = calls.get(0);
        assertThat(begin.sql()).isEqualTo(JdbcBookingFeeRefunds.BEGIN_MANY);
        assertThat((UUID[]) begin.params().get("users")).as("sorted and distinct").containsExactly(CUSTOMER_A,
                CUSTOMER_B);
        assertThat(begin.params().get("correlation")).isEqualTo(correlationId);
        assertThat(calls.stream().filter(c -> c.sql().contains("begin_command"))).as("one Loyalty context").hasSize(1);

        List<Call> records = calls.subList(1, 4);
        assertThat(records).allSatisfy(c -> assertThat(c.sql()).isEqualTo(JdbcBookingFeeRefunds.RECORD));
        assertThat(records).extracting(c -> c.params().get("booking")).as("customer, then booking order")
                .containsExactly(BOOKING_2, BOOKING_3, BOOKING_1);
        assertThat(records).extracting(c -> c.params().get("history"))
                .containsExactly(HISTORY_2, HISTORY_3, HISTORY_1);

        assertThat(result).containsExactlyEntriesOf(refundIds);
        assertThat(result.keySet()).containsExactlyInAnyOrder(BOOKING_1, BOOKING_2, BOOKING_3);
        assertThatThrownBy(() -> result.put(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theOrderDoesNotDependOnTheInputOrder() {
        List<Decision> one = List.of(new Decision(CUSTOMER_B, BOOKING_1, HISTORY_1),
                new Decision(CUSTOMER_A, BOOKING_3, HISTORY_3), new Decision(CUSTOMER_A, BOOKING_2, HISTORY_2));
        List<Decision> other = List.of(new Decision(CUSTOMER_A, BOOKING_2, HISTORY_2),
                new Decision(CUSTOMER_A, BOOKING_3, HISTORY_3), new Decision(CUSTOMER_B, BOOKING_1, HISTORY_1));

        refunds.recordDecisions(command, one);
        List<Call> first = List.copyOf(calls);
        calls.clear();
        refunds.recordDecisions(command, other);

        assertThat(calls).hasSameSizeAs(first);
        for (int i = 0; i < first.size(); i++) {
            assertThat(calls.get(i).sql()).isEqualTo(first.get(i).sql());
            assertThat(calls.get(i).params().get("booking")).isEqualTo(first.get(i).params().get("booking"));
        }
    }

    @Test
    void theSingleCustomerDecisionIsUnchanged() {
        UUID refund = refunds.recordDecision(command, CUSTOMER_A, BOOKING_1, HISTORY_1);

        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).sql())
                .isEqualTo("SELECT loyalty.begin_command(ARRAY[CAST(:user AS uuid)], :correlation)");
        assertThat(calls.get(0).params()).containsEntry("user", CUSTOMER_A)
                .containsEntry("correlation", correlationId).hasSize(2);
        assertThat(calls.get(1).sql()).isEqualTo("SELECT loyalty.record_refund(:booking, :history, NULL)");
        assertThat(calls.get(1).params()).containsEntry("booking", BOOKING_1).containsEntry("history", HISTORY_1)
                .hasSize(2);
        assertThat(refund).isEqualTo(refundIds.get(BOOKING_1));
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    /**
     * A JdbcClient that records each statement and its parameters: {@code singleRow()} answers an empty row, a
     * {@code query(UUID.class).single()} a new refund id, remembered per booking.
     */
    private JdbcClient recordingJdbc() {
        JdbcClient jdbc = mock(JdbcClient.class);
        when(jdbc.sql(anyString())).thenAnswer(sql -> statement(sql.getArgument(0)));
        return jdbc;
    }

    private JdbcClient.StatementSpec statement(String sql) {
        Call call = new Call(sql, new LinkedHashMap<>());
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class, (Answer<Object>) invocation -> {
            String method = invocation.getMethod().getName();
            Object[] args = invocation.getArguments();
            if (method.equals("param") && args.length == 2 && args[0] instanceof String name) {
                call.params().put(name, args[1]);
                return invocation.getMock();
            }
            if (method.equals("query") && args.length == 0) {
                calls.add(call);
                JdbcClient.ResultQuerySpec rows = mock(JdbcClient.ResultQuerySpec.class);
                when(rows.singleRow()).thenReturn(Map.of());
                return rows;
            }
            if (method.equals("query") && args.length == 1 && args[0] == UUID.class) {
                calls.add(call);
                @SuppressWarnings("unchecked")
                JdbcClient.MappedQuerySpec<UUID> mapped = mock(JdbcClient.MappedQuerySpec.class);
                UUID refund = UUID.randomUUID();
                refundIds.put((UUID) call.params().get("booking"), refund);
                when(mapped.single()).thenReturn(refund);
                return mapped;
            }
            throw new AssertionError("unexpected JdbcClient call: " + invocation);
        });
        return spec;
    }
}
