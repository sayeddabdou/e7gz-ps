package ps.e7gz.support.boundary;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.scheduling.api.CommandJobHandler;
import ps.e7gz.scheduling.api.JobOutcome;
import ps.e7gz.scheduling.api.NewJob;
import ps.e7gz.scheduling.api.ScheduledJob;
import ps.e7gz.scheduling.api.ScheduledJobs;
import ps.e7gz.support.IntegrationTest;

/**
 * {@link DueJobs} on a job type of its own, whose handler follows the boundary rule of every command job (arch 08
 * section 2.2): the real worker fires it with "now" exactly where the test put it (E7-45).
 */
class DueJobsIntegrationTest extends IntegrationTest {

    private static final CommandScope NO_LOCKS = CommandScope.shared(LockSet.empty());

    @Autowired
    ApplicationContext context;

    @Autowired
    CommandRunner runner;

    @Autowired
    ScheduledJobs jobs;

    private final String type = "T_BOUNDARY_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    /** The subject's authoritative deadline, by subject. */
    private final Map<UUID, Instant> deadlines = new ConcurrentHashMap<>();
    /** The instant the effect was applied as of, by subject, and how many times. */
    private final Map<UUID, Instant> effectAsOf = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> effects = new ConcurrentHashMap<>();
    /** The command instant each fire saw. */
    private final Map<UUID, Instant> firedAt = new ConcurrentHashMap<>();

    private CorrelationId.Scope correlation;

    @BeforeEach
    void openCorrelation() {
        correlation = CorrelationId.open(CorrelationId.generate());
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    private final CommandJobHandler handler = new CommandJobHandler() {
        @Override
        public String type() {
            return type;
        }

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public LockSet lockSet(ScheduledJob job) {
            return LockSet.empty();
        }

        @Override
        public JobOutcome fire(CommandContext command, ScheduledJob job) {
            UUID subject = job.subjectId();
            firedAt.put(subject, command.commandNow());
            Instant deadline = deadlines.get(subject);
            if (command.commandNow().isBefore(deadline)) {
                return JobOutcome.rescheduleAt(deadline);
            }
            effectAsOf.put(subject, deadline);
            effects.computeIfAbsent(subject, s -> new AtomicInteger()).incrementAndGet();
            return JobOutcome.done();
        }
    };

    @ParameterizedTest
    @EnumSource(Boundary.class)
    void theWorkerFiresWithNowOnTheChosenSideOfTheDeadline(Boundary side) {
        DueJobs due = DueJobs.of(context, handler);
        UUID subject = scheduled(Duration.ofMinutes(10));

        Instant deadline = due.fireAt(NO_LOCKS, key(subject), side, (command, at) -> deadlines.put(subject, at));

        Instant now = firedAt.get(subject);
        assertThat(now).as("fired on the chosen side").isNotNull();
        assertThat(now.isBefore(deadline)).isEqualTo(side.beforeDeadline());
        BoundaryAssertions.assertWithinOneSecond(now, side.now(deadline));
        DueJobs.JobRow job = DueJobs.job(key(subject));
        if (side.beforeDeadline()) {
            assertThat(job.status()).isEqualTo("PENDING");
            assertThat(job.dueAt()).as("rescheduled to the deadline").isEqualTo(deadline);
            assertThat(effects).doesNotContainKey(subject);
        } else {
            assertThat(job.status()).isEqualTo("DONE");
            assertThat(effectAsOf.get(subject)).isEqualTo(deadline);
        }
    }

    @Test
    void firingFiveMinutesLateHasTheSameOutcomeAsOnTime() {
        DueJobs due = DueJobs.of(context, handler);
        UUID onTime = scheduled(Duration.ofMinutes(10));
        UUID late = scheduled(Duration.ofMinutes(10));

        Instant onTimeDeadline = due.fireAt(NO_LOCKS, key(onTime), Boundary.AT, (c, at) -> deadlines.put(onTime, at));
        Instant lateDeadline = runner.run(TestCommands.under(NO_LOCKS, command -> {
            Instant at = command.commandNow().minus(Duration.ofMinutes(5));
            deadlines.put(late, at);
            due.makeDue(command, key(late), at);
            return at;
        }));
        assertThat(due.fire(key(late))).isTrue();

        assertThat(DueJobs.job(key(late)).status()).isEqualTo(DueJobs.job(key(onTime)).status()).isEqualTo("DONE");
        assertThat(effectAsOf.get(late)).as("as of its deadline, not of when it ran").isEqualTo(lateDeadline);
        assertThat(effectAsOf.get(onTime)).isEqualTo(onTimeDeadline);
    }

    @Test
    void firingTheSameJobTwiceAppliesTheEffectOnce() {
        DueJobs due = DueJobs.of(context, handler);
        UUID subject = scheduled(Duration.ofMinutes(10));

        due.fireAt(NO_LOCKS, key(subject), Boundary.AT, (c, at) -> deadlines.put(subject, at));

        assertThat(due.fire(key(subject))).as("a DONE job is not fired again").isFalse();
        assertThat(due.runDue()).isZero();
        assertThat(effects.get(subject)).hasValue(1);
    }

    @Test
    void aJobNotYetDueIsNotFired() {
        DueJobs due = DueJobs.of(context, handler);
        UUID subject = scheduled(Duration.ofMinutes(10));

        assertThat(due.fire(key(subject))).isFalse();
        assertThat(due.runDue()).isZero();
        assertThat(DueJobs.job(key(subject)).status()).isEqualTo("PENDING");
        assertThat(firedAt).doesNotContainKey(subject);
    }

    @Test
    void runDueFiresEveryDueJobOfTheType() {
        DueJobs due = DueJobs.of(context, handler);
        UUID first = scheduled(Duration.ofMinutes(10));
        UUID second = scheduled(Duration.ofMinutes(10));
        runner.run(TestCommands.under(NO_LOCKS, command -> {
            for (UUID subject : new UUID[] {first, second}) {
                deadlines.put(subject, command.commandNow());
                due.makeDue(command, key(subject), command.commandNow());
            }
            return null;
        }));

        assertThat(due.runDue()).isEqualTo(2);
        assertThat(effects).containsOnlyKeys(first, second);
    }

    private UUID scheduled(Duration in) {
        UUID subject = UUID.randomUUID();
        runner.run(TestCommands.under(NO_LOCKS, command -> {
            Instant at = command.commandNow().plus(in);
            deadlines.put(subject, at);
            assertThat(jobs.schedule(command, NewJob.of(type, "TEST", subject, at))).isTrue();
            return null;
        }));
        return subject;
    }

    private String key(UUID subject) {
        return type + ":" + subject;
    }
}
