package ps.e7gz.booking.lateconfirm.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ps.e7gz.scheduling.api.NewJob;

/**
 * FR-LCF-09 and FR-LCF-12 to the exact instant (arch 06 section 2.2, arch 08 sections 1 and 2.6): the rule decided from
 * the initiation's command instant and the booking's start. An instant the database clock cannot be set to, so the
 * rule is tested here; {@code LateConfirmIntegrationTest} checks what the real Initiate commits.
 */
class LateConfirmWindowTest {

    private static final Instant EIGHT_PM = Instant.parse("2026-10-13T17:00:00Z");   // 20:00 Cairo
    private static final UUID BOOKING = UUID.fromString("018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e6f");

    @Test
    void initiatedAt759ForAnEightPmStartTheWindowIsExactlySixtySecondsWithNoWarning() {
        LateConfirmWindow window = LateConfirmWindow.of(EIGHT_PM.minusSeconds(60), EIGHT_PM);

        assertThat(window.expiresAt()).isEqualTo(EIGHT_PM);
        assertThat(window.warningAt()).isEmpty();
        assertThat(window.warningJob(BOOKING)).as("the T3 row is never created").isEmpty();
        assertThat(window.expiryJob(BOOKING)).isEqualTo(NewJob.of("LATE_CONFIRM_EXPIRY", "BOOKING", BOOKING, EIGHT_PM));
    }

    @Test
    void initiatedAt750TheWindowIsFiveMinutesWithOneWarningAMinuteBefore() {
        LateConfirmWindow window = LateConfirmWindow.of(EIGHT_PM.minusSeconds(600), EIGHT_PM);

        assertThat(window.expiresAt()).isEqualTo(EIGHT_PM.minusSeconds(300));                   // 19:55
        assertThat(window.warningAt()).contains(EIGHT_PM.minusSeconds(360));                     // 19:54
        assertThat(window.warningJob(BOOKING)).contains(
                NewJob.of("LATE_CONFIRM_WARNING", "BOOKING", BOOKING, EIGHT_PM.minusSeconds(360)));
    }

    @Test
    void aWindowJustOverSixtySecondsHasItsWarning() {
        Instant initiated = EIGHT_PM.minusSeconds(60).minusNanos(1_000);

        LateConfirmWindow window = LateConfirmWindow.of(initiated, EIGHT_PM);

        assertThat(window.expiresAt()).isEqualTo(EIGHT_PM);
        assertThat(window.warningAt()).contains(EIGHT_PM.minusSeconds(60));
    }

    @Test
    void theWindowNeverPassesTheStartAndIsFiveMinutesOtherwise() {
        assertThat(LateConfirmWindow.of(EIGHT_PM.minusSeconds(30), EIGHT_PM).expiresAt()).isEqualTo(EIGHT_PM);
        assertThat(LateConfirmWindow.of(EIGHT_PM.minusSeconds(30), EIGHT_PM).warningAt()).isEmpty();
        assertThat(LateConfirmWindow.of(EIGHT_PM.minusSeconds(300), EIGHT_PM).expiresAt()).isEqualTo(EIGHT_PM);
        assertThat(LateConfirmWindow.of(EIGHT_PM.minusSeconds(3600), EIGHT_PM).expiresAt())
                .isEqualTo(EIGHT_PM.minusSeconds(3300));
    }

    @Test
    void theJobKeysAreOnePerBooking() {
        assertThat(LateConfirmWindow.expiryKey(BOOKING)).isEqualTo("LATE_CONFIRM_EXPIRY:" + BOOKING);
        assertThat(LateConfirmWindow.warningKey(BOOKING)).isEqualTo("LATE_CONFIRM_WARNING:" + BOOKING);
    }
}
