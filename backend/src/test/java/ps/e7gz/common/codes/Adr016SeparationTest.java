package ps.e7gz.common.codes;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import ps.e7gz.common.error.ApiErrorCode;

/**
 * ADR-016 / arch 01 section 12.1: Booking Terminal Reasons, Booking Events and API error codes are three separate
 * closed types, and a token belongs to at most one of them.
 */
class Adr016SeparationTest {

    private static final List<Class<? extends Enum<?>>> THREE =
            List.of(BookingTerminalReason.class, BookingEventType.class, ApiErrorCode.class);

    /** FR-AUD-15: removed outright - never a member of any closed outcome enum. */
    private static final Set<String> REMOVED_TOKENS = Set.of(
            "BOOKING_EXPIRED", "RESOURCE_CONFLICT", "CUSTOMER_SCHEDULE_CONFLICT", "LATE_CONFIRM_RESOURCE_UNAVAILABLE");

    /**
     * FR-AUD-15's fifth token is removed only as a terminal reason: it is a valid Booking Event (PO clarification of
     * FR-AUD-15; the physical model's canonical booking-history event list contains it). So it is not banned globally.
     */
    private static final String REMOVED_AS_TERMINAL_REASON_ONLY = "LATE_CONFIRM_ELIGIBILITY_LOST_RESOURCE_STATE_CHANGED";

    @Test
    void theThreeAreDistinctEnumTypes() {
        assertThat(THREE).doesNotHaveDuplicates().allSatisfy(type -> {
            assertThat(type.isEnum()).isTrue();
            assertThat(type.getSuperclass()).isEqualTo(Enum.class);
        });
        assertThat(ApiErrorCode.class.getPackageName()).isEqualTo("ps.e7gz.common.error");
    }

    @Test
    void noTokenBelongsToTwoOfTheThree() {
        for (int i = 0; i < THREE.size(); i++) {
            for (int j = i + 1; j < THREE.size(); j++) {
                Set<String> shared = new TreeSet<>(ClosedValueContract.names(THREE.get(i)));
                shared.retainAll(ClosedValueContract.names(THREE.get(j)));
                assertThat(shared).as(THREE.get(i).getSimpleName() + " / " + THREE.get(j).getSimpleName()).isEmpty();
            }
        }
    }

    @Test
    void validationOnlyTokensStayApiErrorCodesOnly() {
        assertThat(ClosedValueContract.names(ApiErrorCode.class)).contains("MANUAL_BOOKING_PRICING_CONFIGURATION_MISSING");
        assertThat(ClosedValueContract.names(BookingEventType.class))
                .doesNotContain("MANUAL_BOOKING_PRICING_CONFIGURATION_MISSING");
        assertThat(ClosedValueContract.names(BookingTerminalReason.class))
                .doesNotContain("MANUAL_BOOKING_PRICING_CONFIGURATION_MISSING");
    }

    @Test
    void theRemovedTokensCannotReturn() {
        for (Class<? extends Enum<?>> type : List.of(BookingTerminalReason.class, BookingEventType.class,
                ApiErrorCode.class, TournamentTerminalReason.class)) {
            assertThat(ClosedValueContract.names(type)).as(type.getSimpleName()).doesNotContainAnyElementsOf(REMOVED_TOKENS);
        }
    }

    @Test
    void theFifthRemovedTokenIsNotATerminalReasonButIsABookingEvent() {
        assertThat(ClosedValueContract.names(BookingTerminalReason.class)).doesNotContain(REMOVED_AS_TERMINAL_REASON_ONLY);
        assertThat(ClosedValueContract.names(BookingEventType.class)).contains(REMOVED_AS_TERMINAL_REASON_ONLY);
    }

    @Test
    void bookingEventTypeHasTheTwelveMembers() {
        assertThat(BookingEventType.values()).hasSize(12);
    }

    @Test
    void tournamentTerminalReasonIsItsOwnClosedType() {
        assertThat(TournamentTerminalReason.class).isNotIn(THREE);
        assertThat(TournamentTerminalReason.values()).hasSize(12);
    }
}
