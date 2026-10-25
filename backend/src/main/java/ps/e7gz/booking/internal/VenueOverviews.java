package ps.e7gz.booking.internal;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import ps.e7gz.booking.api.VenueRevenue;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.money.Money;
import ps.e7gz.inventory.availability.api.BusinessDates;
import ps.e7gz.venue.api.VenueAccountVenues;

/**
 * The four figures of the Venue Dashboard Overview (FR-VEN-35, E7-115) for the signed-in account's own venue.
 *
 * <p>"Today" is {@link BusinessDates#currentBusinessDate} from the injected {@code Clock} (arch 04 section 4.3; PO
 * decision 3 Oct 2026, restated 12 Oct 2026): the Operating Day of the session still running, so a night crossing
 * midnight is one business day; never the Cairo calendar date and never {@code Instant.now()}. "This month" runs from
 * the first of today's business-date month to today. The four figures are read by four statements in one READ
 * COMMITTED transaction (arch 04 section 2.1): each statement sees the data committed when it starts, so a booking
 * committed between two of them can appear in one figure and not yet in another.
 */
@Component
public class VenueOverviews {

    /** FR-VEN-35, arch 09 section 4.1: everything the Overview shows that the Dashboard does not get elsewhere. */
    public record Overview(Money todayRevenue, Money monthRevenue, int todayBookings, int pendingRequests) {

        public Overview {
            Objects.requireNonNull(todayRevenue, "todayRevenue");
            Objects.requireNonNull(monthRevenue, "monthRevenue");
        }
    }

    /** Every booking of the venue on the business date, online and manual, whatever its status (PO 11 Oct 2026). */
    static final String BOOKINGS_ON = """
            SELECT count(*) FROM booking.booking b WHERE b.venue_id = :venueId AND b.business_date = :date
            """;

    /** FR-VEN-34: the same requests as the Pending list ({@code VenueAnswerStore.PENDING_OF_VENUE}). */
    static final String PENDING_COUNT = """
            SELECT count(*) FROM booking.booking b WHERE b.venue_id = :venueId AND b.status = 'PENDING'
            """;

    private final CommandRunner runner;
    private final VenueAccountVenues accounts;
    private final BusinessDates businessDates;
    private final VenueRevenue revenue;

    VenueOverviews(CommandRunner runner, VenueAccountVenues accounts, BusinessDates businessDates,
                   VenueRevenue revenue) {
        this.runner = runner;
        this.accounts = accounts;
        this.businessDates = businessDates;
        this.revenue = revenue;
    }

    public Overview of(UUID venueAccountId) {
        UUID venueId = accounts.venueOf(Objects.requireNonNull(venueAccountId, "venueAccountId"));
        LocalDate today = businessDates.currentBusinessDate(venueId);
        LocalDate firstOfMonth = today.withDayOfMonth(1);
        return runner.run(new Read<>(context -> new Overview(
                revenue.completed(context, venueId, today, today),
                revenue.completed(context, venueId, firstOfMonth, today),
                count(context, BOOKINGS_ON, venueId, today),
                count(context, PENDING_COUNT, venueId, null))));
    }

    private static int count(CommandContext context, String sql, UUID venueId, LocalDate date) {
        var statement = context.jdbc().sql(sql).param("venueId", venueId);
        if (date != null) {
            statement = statement.param("date", date);
        }
        return Math.toIntExact(statement.query(Long.class).single());
    }

    /** A read-only command: one READ COMMITTED transaction, no locks (as {@code VenueBookings}' reads). */
    private record Read<R>(Function<CommandContext, R> body) implements Command<R> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.empty());
        }

        @Override
        public R execute(CommandContext context) {
            return body.apply(context);
        }
    }
}
