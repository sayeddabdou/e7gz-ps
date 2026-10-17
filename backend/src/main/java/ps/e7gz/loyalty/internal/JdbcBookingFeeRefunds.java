package ps.e7gz.loyalty.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.loyalty.api.BookingFeeRefunds;

/**
 * {@code loyalty.begin_command} and {@code loyalty.record_refund} on the command's transaction: the producer functions
 * are the only writers of {@code loyalty.booking_fee_refund} (ADR-026). No admin service-loss assessment: the reasons
 * a venue answer ends in are never ADMIN_*.
 */
@Component
class JdbcBookingFeeRefunds implements BookingFeeRefunds {

    static final String BEGIN = "SELECT loyalty.begin_command(ARRAY[CAST(:user AS uuid)], :correlation)";
    /**
     * E7-113: every Customer of a batch in one context. A {@code UUID[]} parameter goes to the driver as is (only an
     * {@code Iterable} is expanded by Spring) and pgjdbc sends it as an untyped array literal - the cast types it.
     */
    static final String BEGIN_MANY = "SELECT loyalty.begin_command(CAST(:users AS uuid[]), :correlation)";
    static final String RECORD = "SELECT loyalty.record_refund(:booking, :history, NULL)";

    /** The order a batch is recorded in: by Customer, as the lock set orders them, then by booking. */
    static final Comparator<Decision> ORDER =
            Comparator.comparing(Decision::customerId).thenComparing(Decision::bookingId);

    @Override
    public UUID recordDecision(CommandContext command, UUID customerId, UUID bookingId, UUID terminalHistoryId) {
        Objects.requireNonNull(command, "command");
        command.jdbc().sql(BEGIN)
                .param("user", Objects.requireNonNull(customerId, "customerId"))
                .param("correlation", CorrelationId.require())
                .query().singleRow();
        return command.jdbc().sql(RECORD)
                .param("booking", Objects.requireNonNull(bookingId, "bookingId"))
                .param("history", Objects.requireNonNull(terminalHistoryId, "terminalHistoryId"))
                .query(UUID.class).single();
    }

    @Override
    public Map<UUID, UUID> recordDecisions(CommandContext command, List<Decision> decisions) {
        Objects.requireNonNull(command, "command");
        List<Decision> ordered = validated(decisions);
        if (ordered.isEmpty()) {
            return Map.of();
        }
        command.jdbc().sql(BEGIN_MANY)
                .param("users", ordered.stream().map(Decision::customerId).distinct().sorted().toArray(UUID[]::new))
                .param("correlation", CorrelationId.require())
                .query().singleRow();
        Map<UUID, UUID> refunds = new LinkedHashMap<>();
        for (Decision decision : ordered) {
            refunds.put(decision.bookingId(), command.jdbc().sql(RECORD)
                    .param("booking", decision.bookingId())
                    .param("history", decision.terminalHistoryId())
                    .query(UUID.class).single());
        }
        return Collections.unmodifiableMap(refunds);
    }

    /** The whole batch checked before any SQL, in recording order; a booking twice is a caller defect. */
    private static List<Decision> validated(List<Decision> decisions) {
        Objects.requireNonNull(decisions, "decisions");
        List<Decision> ordered = new ArrayList<>(decisions.size());
        Set<UUID> bookings = new HashSet<>();
        for (Decision decision : decisions) {
            Objects.requireNonNull(decision, "decisions must not contain null");
            if (!bookings.add(decision.bookingId())) {
                throw new IllegalArgumentException("Booking " + decision.bookingId() + " appears twice in the batch");
            }
            ordered.add(decision);
        }
        ordered.sort(ORDER);
        return ordered;
    }
}
