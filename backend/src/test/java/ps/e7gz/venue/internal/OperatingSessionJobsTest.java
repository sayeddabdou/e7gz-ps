package ps.e7gz.venue.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import ps.e7gz.scheduling.api.Recurrence;
import ps.e7gz.scheduling.api.RecurringJob;

/**
 * E7-40: the nightly Operating Session horizon extension (ADR-009, E7-25) is a recurring system job of the worker on
 * technical.scheduled_job, at 03:15 Africa/Cairo by default. The extension itself is tested in OpeningHoursHttpTest.
 */
class OperatingSessionJobsTest {

    @Test
    void theHorizonIsExtendedEveryNightThroughTheScheduler() {
        OpeningHoursService hours = mock(OpeningHoursService.class);
        when(hours.extendAll()).thenReturn(3);

        RecurringJob job = new OperatingSessionJobs(hours)
                .operatingSessionHorizonJob(new OperatingSessionProperties(120, LocalTime.of(3, 15)));
        job.run(null);

        assertThat(job.type()).isEqualTo("OPERATING_SESSION_HORIZON");
        assertThat(job.recurrence()).isEqualTo(Recurrence.nightlyAt(LocalTime.of(3, 15)));
        verify(hours).extendAll();
    }
}
