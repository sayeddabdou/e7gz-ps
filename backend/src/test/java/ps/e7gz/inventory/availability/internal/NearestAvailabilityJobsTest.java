package ps.e7gz.inventory.availability.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.inventory.availability.internal.NearestAvailabilityProjectionService.RefreshPass;
import ps.e7gz.scheduling.api.Recurrence;
import ps.e7gz.scheduling.api.RecurringJob;
import ps.e7gz.venue.api.VenueActivities;
import ps.e7gz.venue.api.VenueActivities.Offering;

/**
 * The worker's Nearest Availability pass (PO decision 8 Oct 2026, Sprint 1 review): every pair, a failing one skipped,
 * as a recurring job of the worker profile only, every configurable minute (E7-40: on technical.scheduled_job). The
 * database side is in {@code NearestAvailabilityIntegrationTest.theWorkerPassRefreshesAStaleRowWithoutAnyEvent}.
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
    void thePassIsARecurringJobOfTheWorkerProfileEveryMinuteByDefault() throws NoSuchMethodException {
        assertThat(NearestAvailabilityJobs.class.getAnnotation(Profile.class).value()).containsExactly("worker");
        Value interval = NearestAvailabilityJobs.class
                .getDeclaredMethod("nearestAvailabilityRefreshJob", Duration.class)
                .getParameters()[0].getAnnotation(Value.class);
        assertThat(interval.value()).isEqualTo("${e7gz.availability.projection.refresh-every:1m}");

        NearestAvailabilityProjectionService service = mock(NearestAvailabilityProjectionService.class);
        when(service.refreshEveryPair()).thenReturn(new RefreshPass(2, 0));
        RecurringJob job = new NearestAvailabilityJobs(service).nearestAvailabilityRefreshJob(Duration.ofMinutes(1));

        assertThat(job.type()).isEqualTo("NEAREST_AVAILABILITY_REFRESH");
        assertThat(job.recurrence()).isEqualTo(Recurrence.every(Duration.ofMinutes(1)));
        job.run(null);
        verify(service).refreshEveryPair();
    }
}
