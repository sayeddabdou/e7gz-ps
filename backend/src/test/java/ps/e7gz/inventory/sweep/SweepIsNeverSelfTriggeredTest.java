package ps.e7gz.inventory.sweep;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import ps.e7gz.inventory.sweep.api.InvariantCheck;
import ps.e7gz.inventory.sweep.api.InvariantSeverity;
import ps.e7gz.inventory.sweep.api.InvariantSweep;
import ps.e7gz.inventory.sweep.api.InvariantViolation;
import ps.e7gz.support.IntegrationTest;

/**
 * E7-32 - the sweep runs only when its entry point is called (by the E7-40 worker job later). Nothing in this
 * package schedules, polls or starts it, and starting the {@code api} profile does not run it.
 */
@ActiveProfiles("api")
@Import(SweepIsNeverSelfTriggeredTest.CountingCheckConfig.class)
class SweepIsNeverSelfTriggeredTest extends IntegrationTest {

    static final AtomicInteger DETECT_CALLS = new AtomicInteger();

    @TestConfiguration
    static class CountingCheckConfig {

        /** Counts every run of the sweep, whatever starts it. */
        @Bean
        InvariantCheck countingCheck() {
            return new InvariantCheck() {
                @Override
                public String invariant() {
                    return "TEST_COUNTING_CHECK";
                }

                @Override
                public InvariantSeverity severity() {
                    return InvariantSeverity.SEV_1;
                }

                @Override
                public List<InvariantViolation> detect(JdbcClient snapshot) {
                    DETECT_CALLS.incrementAndGet();
                    return List.of();
                }
            };
        }
    }

    @Autowired
    InvariantSweep sweep;

    @Test
    void startingTheApiProfileDoesNotRunTheSweepButCallingItsEntryPointDoes() {
        assertThat(DETECT_CALLS.get()).as("the api context started without running the sweep").isZero();

        sweep.run();

        assertThat(DETECT_CALLS.get()).as("the counting check is wired into the sweep").isEqualTo(1);
    }

    @Test
    void nothingInTheSweepSchedulesOrStartsItself() {
        JavaClasses sweepClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("ps.e7gz.inventory.sweep");
        assertThat(sweepClasses).as("production sweep classes imported").isNotEmpty();

        noClasses().should().dependOnClassesThat().resideInAPackage("org.springframework.scheduling..")
                .as("no @Scheduled / @EnableScheduling / TaskScheduler: E7-40 runs the sweep as a scheduled_job")
                .check(sweepClasses);
        noMethods().should().beAnnotatedWith("org.springframework.context.event.EventListener")
                .orShould().beAnnotatedWith("jakarta.annotation.PostConstruct")
                .as("no start-up or event hook runs the sweep")
                .check(sweepClasses);
        noClasses().should().beAssignableTo(org.springframework.boot.ApplicationRunner.class)
                .orShould().beAssignableTo(org.springframework.boot.CommandLineRunner.class)
                .orShould().beAssignableTo(org.springframework.context.ApplicationListener.class)
                .orShould().beAssignableTo(org.springframework.context.Lifecycle.class)
                .orShould().beAssignableTo(org.springframework.beans.factory.InitializingBean.class)
                .orShould().beAssignableTo(org.springframework.beans.factory.SmartInitializingSingleton.class)
                .as("no runner or lifecycle callback runs the sweep")
                .check(sweepClasses);
    }
}
