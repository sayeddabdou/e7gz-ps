package ps.e7gz.inventory.availability.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.inventory.availability.internal.NearestAvailabilityProjectionService.RefreshPass;
import ps.e7gz.venue.api.VenueActivities;
import ps.e7gz.venue.api.VenueActivities.Offering;

/**
 * The worker's Nearest Availability pass (PO decision 8 Oct 2026, Sprint 1 review): every pair, a failing one skipped,
 * on a configurable one-minute timer of the worker profile only. The database side is in
 * {@code NearestAvailabilityIntegrationTest.theWorkerPassRefreshesAStaleRowWithoutAnyEvent}.
 */
class NearestAvailabilityJobsTest {

    @Test
    void aFailingPairIsSkippedAndEveryOtherPairIsStillRefreshed() {
        CommandRunner runner = mock(CommandRunner.class);
        VenueActivities activities = mock(VenueActivities.class);
        when(activities.all()).thenReturn(List.of(new Offering(UUID.randomUUID(), Activity.PLAYSTATION),
                new Offering(UUID.randomUUID(), Activity.BILLIARDS),
                new Offering(UUID.randomUUID(), Activity.PING_PONG)));
        when(runner.run(any())).thenThrow(new IllegalStateException("one bad pair"))
                .thenReturn(Optional.empty()).thenReturn(Optional.empty());
        NearestAvailabilityProjectionService service = new NearestAvailabilityProjectionService(runner,
                mock(NearestAvailabilityCalculator.class), mock(JdbcAvailabilityProjectionStore.class), activities);

        RefreshPass pass = service.refreshEveryPair();

        assertThat(pass).isEqualTo(new RefreshPass(3, 1));
        verify(runner, times(3)).run(any());
    }

    @Test
    void theTimerRunsOnlyInTheWorkerProfileEveryMinuteByDefault() throws NoSuchMethodException {
        assertThat(NearestAvailabilityJobs.class.getAnnotation(Profile.class).value()).containsExactly("worker");
        Scheduled timer = NearestAvailabilityJobs.class.getDeclaredMethod("refreshEveryPair")
                .getAnnotation(Scheduled.class);
        assertThat(timer.fixedDelayString()).isEqualTo("${e7gz.availability.projection.refresh-every:1m}");
    }
}
