package ps.e7gz.scheduling.internal;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.test.context.ActiveProfiles;
import ps.e7gz.scheduling.api.ScheduledJobHandler;
import ps.e7gz.scheduling.api.ScheduledJobs;
import ps.e7gz.support.IntegrationTest;

/**
 * E7-40: the deadline worker and every timer exist only in the {@code worker} profile (arch 12 section 1.3); the
 * {@code api} profile schedules jobs but starts no worker thread. No Spring timer ({@code @Scheduled},
 * {@code @EnableScheduling}, {@code TaskScheduler}) is left anywhere: every timer is a {@code technical.scheduled_job}.
 */
@ActiveProfiles("api")
class SchedulerProfilesTest extends IntegrationTest {

    /** Every class that declares worker-only timer beans or the worker itself. */
    private static final List<String> WORKER_ONLY = List.of(
            "ps.e7gz.scheduling.internal.SchedulingWorkerConfig",
            "ps.e7gz.venue.internal.VenuePhotoJobs",
            "ps.e7gz.venue.internal.OperatingSessionJobs",
            "ps.e7gz.inventory.availability.internal.NearestAvailabilityJobs");

    @Autowired
    ApplicationContext context;

    @Test
    void theApiProfileSchedulesJobsButStartsNoWorker() {
        assertThat(context.getBeansOfType(ScheduledJobs.class)).hasSize(1);
        assertThat(context.getBeansOfType(SchedulerLoop.class)).isEmpty();
        assertThat(context.getBeansOfType(ScheduledJobHandler.class)).isEmpty();
        assertThat(Thread.getAllStackTraces().keySet()).noneMatch(t -> t.getName().startsWith("e7gz-scheduler-"));
    }

    @Test
    void theWorkerAndEveryTimerBelongToTheWorkerProfile() throws ClassNotFoundException {
        for (String name : WORKER_ONLY) {
            Profile profile = Class.forName(name).getAnnotation(Profile.class);
            assertThat(profile).as(name).isNotNull();
            assertThat(profile.value()).as(name).containsExactly("worker");
        }
    }

    @Test
    void noSpringTimerIsLeftAnywhere() {
        JavaClasses production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("ps.e7gz");

        noClasses().should().dependOnClassesThat().resideInAPackage("org.springframework.scheduling..")
                .as("no @Scheduled / @EnableScheduling / TaskScheduler: every timer is a technical.scheduled_job (E7-40)")
                .check(production);
    }
}
