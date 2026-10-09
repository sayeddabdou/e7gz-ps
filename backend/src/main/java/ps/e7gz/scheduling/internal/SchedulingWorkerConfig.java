package ps.e7gz.scheduling.internal;

import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.inventory.sweep.api.InvariantSweep;
import ps.e7gz.scheduling.api.Recurrence;
import ps.e7gz.scheduling.api.RecurringJob;
import ps.e7gz.scheduling.api.ScheduledJobHandler;

/**
 * The deadline worker exists only in the {@code worker} profile (arch 12 section 1.3): the {@code api} profile schedules
 * jobs but never fires one.
 */
@Configuration(proxyBeanMethods = false)
@Profile("worker")
@EnableConfigurationProperties(SchedulerProperties.class)
class SchedulingWorkerConfig {

    static final String INVARIANT_SWEEP = "INVARIANT_SWEEP";

    @Bean
    SchedulerLoop schedulerLoop(List<ScheduledJobHandler> handlers, JdbcScheduledJobStore store, CommandRunner runner,
                                DataSource appDataSource, SchedulerProperties properties, Clock clock) {
        JobHandlerRegistry registry = new JobHandlerRegistry(handlers);
        String process = ManagementFactory.getRuntimeMXBean().getName();      // pid@host
        List<DeadlineWorker> workers = new ArrayList<>();
        for (int i = 1; i <= properties.threads(); i++) {
            workers.add(new DeadlineWorker(process + "/" + i, registry, store, runner, appDataSource, properties, clock));
        }
        return new SchedulerLoop(workers, appDataSource, properties.tick());
    }

    /**
     * The nightly integrity sweep (arch 11 section 4, NFR-26, NFR-28; PO decision 4 Oct 2026): it calls only
     * {@link InvariantSweep#run()}, outside any transaction - the sweep opens its own read-only snapshot - and the job is
     * finished in its own transaction afterwards.
     */
    @Bean
    RecurringJob invariantSweepJob(InvariantSweep sweep, SchedulerProperties properties) {
        return new RecurringJob(INVARIANT_SWEEP, Recurrence.nightlyAt(properties.invariantSweepAt()), sweep::run);
    }
}
