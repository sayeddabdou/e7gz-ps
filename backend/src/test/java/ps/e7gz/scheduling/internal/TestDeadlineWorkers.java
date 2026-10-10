package ps.e7gz.scheduling.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.ApplicationContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.scheduling.api.ScheduledJobHandler;

/**
 * TEST-ONLY: a real {@link DeadlineWorker} over the given handlers (E7-45). The {@code worker} profile is off in tests,
 * so no worker fires anything on its own; a test polls this one when it wants due jobs fired. Tests reach it through
 * {@code ps.e7gz.support.boundary.DueJobs}.
 */
public final class TestDeadlineWorkers {

    /** Default settings, one thread; five attempts so a handler bug shows as a retried, not a FAILED, job. */
    private static final SchedulerProperties PROPERTIES = new SchedulerProperties(Duration.ofSeconds(1), 1, 10,
            Duration.ofMinutes(5), 5, Duration.ofSeconds(10), Duration.ofMinutes(10), LocalTime.of(4, 0));

    private TestDeadlineWorkers() {
    }

    /** What a test may ask of the worker. */
    public interface Worker {

        /** One poll: fires every due job of the handlers' types; returns how many it settled. */
        int fireDueJobs();

        /** Fires this one job by the same path, if it is due and free; returns whether it was settled. */
        boolean fireIfDue(UUID jobId);
    }

    public static Worker worker(ApplicationContext context, List<? extends ScheduledJobHandler> handlers) {
        DeadlineWorker worker = new DeadlineWorker("boundary-pack", new JobHandlerRegistry(List.copyOf(handlers)),
                context.getBean(JdbcScheduledJobStore.class), context.getBean(CommandRunner.class),
                context.getBean("appDataSource", DataSource.class), PROPERTIES, context.getBean(Clock.class));
        return new Worker() {
            @Override
            public int fireDueJobs() {
                return worker.fireDueJobs();
            }

            @Override
            public boolean fireIfDue(UUID jobId) {
                return worker.fireIfDue(jobId);
            }
        };
    }
}
