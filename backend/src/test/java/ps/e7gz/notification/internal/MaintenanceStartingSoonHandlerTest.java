package ps.e7gz.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ps.e7gz.notification.api.NotifiedMaintenance.Window;
import ps.e7gz.notification.internal.MaintenanceStartingSoonHandler.Decision;

/** E7-80 (PO decision 25 Nov 2026, Q4): "Maintenance Starting Soon" 30 minutes before the window's start. */
class MaintenanceStartingSoonHandlerTest {

    private static final Instant START = Instant.parse("2026-12-10T18:00:00Z");
    private static final UUID ID = UUID.randomUUID();

    private static Optional<Window> window(Instant start, boolean deleted) {
        return Optional.of(new Window(ID, UUID.randomUUID(), UUID.randomUUID(), start, deleted));
    }

    @Test
    void theLeadIsThirtyMinutes() {
        assertThat(MaintenanceStartingSoonHandler.LEAD).isEqualTo(Duration.ofMinutes(30));
        assertThat(MaintenanceStartingSoonHandler.key(ID, START))
                .isEqualTo("VENUE_MAINTENANCE_STARTING_SOON:" + ID + ":" + START.toEpochMilli());
    }

    @Test
    void sendsAtThirtyMinutesBefore() {
        assertThat(MaintenanceStartingSoonHandler.decide(window(START, false), START, START.minus(Duration.ofMinutes(30))))
                .isEqualTo(Decision.SEND);
    }

    @Test
    void notYetBeforeTheDueTime() {
        assertThat(MaintenanceStartingSoonHandler.decide(window(START, false), START,
                START.minus(Duration.ofMinutes(31)))).isEqualTo(Decision.NOT_YET);
    }

    @Test
    void aMovedWindowSupersedesTheOldJob() {
        assertThat(MaintenanceStartingSoonHandler.decide(window(START.plus(Duration.ofHours(1)), false), START,
                START.minus(Duration.ofMinutes(10)))).isEqualTo(Decision.SUPERSEDED);
    }

    @Test
    void aDeletedOrMissingWindowSupersedes() {
        assertThat(MaintenanceStartingSoonHandler.decide(window(START, true), START, START.minus(Duration.ofMinutes(5))))
                .isEqualTo(Decision.SUPERSEDED);
        assertThat(MaintenanceStartingSoonHandler.decide(Optional.empty(), START, START)).isEqualTo(Decision.SUPERSEDED);
    }

    @Test
    void aStartedWindowSupersedes() {
        assertThat(MaintenanceStartingSoonHandler.decide(window(START, false), START, START))
                .as("no warning after the fact").isEqualTo(Decision.SUPERSEDED);
    }
}
