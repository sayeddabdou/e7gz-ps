package ps.e7gz.inventory.availability.internal;

import java.time.Clock;
import ps.e7gz.inventory.availability.api.BusinessDates;
import ps.e7gz.venue.api.OperatingSessions;

/** E7-31 tests: the production resolver over the real owner read, with a test's own fixed {@link Clock}. */
public final class BusinessDatesWithClock {

    private BusinessDatesWithClock() {
    }

    public static BusinessDates of(OperatingSessions sessions, Clock clock) {
        return new SessionBusinessDates(sessions, clock);
    }
}
