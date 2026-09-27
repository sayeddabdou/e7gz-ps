package ps.e7gz.common.command;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ps.e7gz.common.command.archfixture.ClockCommand;
import ps.e7gz.common.command.archfixture.InfrastructureClockWorker;
import ps.e7gz.common.command.archfixture.InfrastructureTransactionWorker;
import ps.e7gz.common.command.archfixture.InstantNowCommand;
import ps.e7gz.common.command.archfixture.PlainCommand;
import ps.e7gz.common.command.archfixture.ReadOnlyTransactionalCommand;
import ps.e7gz.common.command.archfixture.SelfTransactionalCommand;

/**
 * A {@link Command} never owns its transaction or its clock: {@link CommandRunner} does (arch 04 section 2.1,
 * ADR-026 section 3).
 * <ul>
 *   <li>A Command implementation must not declare any {@code @Transactional} (read-only included) and must not use a
 *       transaction manager or template of its own.</li>
 *   <li>A Command implementation must not depend on {@link Clock} or call {@link Instant#now()}: rule times inside a
 *       command come from {@link CommandContext#commandNow()}. {@link Instant} values themselves are fine.</li>
 * </ul>
 *
 * <p>Scoped to Command implementations on purpose: technical/infrastructure code that is not a Command (scheduling,
 * outbox, dispatchers, workers) is not restricted by these rules. Calling the runner from inside another transaction
 * is refused at runtime by {@link CommandRunner#run}.
 */
class CommandBoundaryArchitectureTest {

    private static final Set<String> TRANSACTIONAL =
            Set.of("org.springframework.transaction.annotation.Transactional", "jakarta.transaction.Transactional");

    private static final Set<String> TRANSACTION_APIS = Set.of(
            "org.springframework.transaction.TransactionManager",
            "org.springframework.transaction.PlatformTransactionManager",
            "org.springframework.transaction.support.TransactionTemplate",
            "org.springframework.transaction.support.TransactionOperations",
            "org.springframework.jdbc.datasource.DataSourceTransactionManager",
            "org.springframework.jdbc.support.JdbcTransactionManager");

    private static final DescribedPredicate<JavaAnnotation<?>> ANY_TRANSACTIONAL =
            DescribedPredicate.describe("@Transactional (any, readOnly included)",
                    annotation -> TRANSACTIONAL.contains(annotation.getRawType().getName()));

    private static final DescribedPredicate<JavaClass> TRANSACTION_API =
            DescribedPredicate.describe("a transaction manager or template",
                    type -> TRANSACTION_APIS.contains(type.getName()));

    static final ArchRule COMMANDS_ARE_NOT_TRANSACTIONAL = noClasses()
            .that().areAssignableTo(Command.class)
            .should().beAnnotatedWith(ANY_TRANSACTIONAL)
            .as("a Command runs in the CommandRunner's transaction, not a class-level @Transactional");

    static final ArchRule COMMAND_METHODS_ARE_NOT_TRANSACTIONAL = noMethods()
            .that().areDeclaredInClassesThat().areAssignableTo(Command.class)
            .should().beAnnotatedWith(ANY_TRANSACTIONAL)
            .as("a Command runs in the CommandRunner's transaction, not a method-level @Transactional");

    static final ArchRule COMMANDS_OPEN_NO_TRANSACTION = noClasses()
            .that().areAssignableTo(Command.class)
            .should().dependOnClassesThat(TRANSACTION_API)
            .as("a Command runs in the CommandRunner's transaction, not one it opens itself");

    static final ArchRule COMMANDS_USE_NO_CLOCK = noClasses()
            .that().areAssignableTo(Command.class)
            .should().dependOnClassesThat().areAssignableTo(Clock.class)
            .as("a Command reads time from CommandContext.commandNow(), not a Clock");

    static final ArchRule COMMANDS_DO_NOT_CALL_INSTANT_NOW = noClasses()
            .that().areAssignableTo(Command.class)
            .should().callMethod(Instant.class, "now")
            .as("a Command reads time from CommandContext.commandNow(), not Instant.now()");

    private static final List<ArchRule> TRANSACTION_RULES =
            List.of(COMMANDS_ARE_NOT_TRANSACTIONAL, COMMAND_METHODS_ARE_NOT_TRANSACTIONAL, COMMANDS_OPEN_NO_TRANSACTION);

    private static final List<ArchRule> TIME_RULES = List.of(COMMANDS_USE_NO_CLOCK, COMMANDS_DO_NOT_CALL_INSTANT_NOW);

    private static final List<ArchRule> ALL_RULES =
            Stream.concat(TRANSACTION_RULES.stream(), TIME_RULES.stream()).toList();

    private static JavaClasses production;

    @BeforeAll
    static void importProductionClasses() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("ps.e7gz");
    }

    @Test
    void noProductionCommandOwnsItsTransactionOrItsClock() {
        ALL_RULES.forEach(rule -> rule.check(production));
    }

    @Test
    void aCommandOpeningItsOwnTransactionFailsEveryTransactionRule() {
        JavaClasses violating = new ClassFileImporter().importClasses(SelfTransactionalCommand.class);

        TRANSACTION_RULES.forEach(rule ->
                assertThat(rule.evaluate(violating).hasViolation()).as(rule.getDescription()).isTrue());
    }

    @Test
    void aCommandDeclaringEvenAReadOnlyTransactionIsRejected() {
        JavaClasses readOnly = new ClassFileImporter().importClasses(ReadOnlyTransactionalCommand.class);

        assertThat(COMMANDS_ARE_NOT_TRANSACTIONAL.evaluate(readOnly).hasViolation()).isTrue();
        assertThat(COMMAND_METHODS_ARE_NOT_TRANSACTIONAL.evaluate(readOnly).hasViolation()).isTrue();
    }

    @Test
    void aCommandUsingAClockIsRejected() {
        JavaClasses clock = new ClassFileImporter().importClasses(ClockCommand.class);

        assertThat(COMMANDS_USE_NO_CLOCK.evaluate(clock).hasViolation()).isTrue();
    }

    @Test
    void aCommandCallingInstantNowIsRejected() {
        JavaClasses instantNow = new ClassFileImporter().importClasses(InstantNowCommand.class);

        assertThat(COMMANDS_DO_NOT_CALL_INSTANT_NOW.evaluate(instantNow).hasViolation()).isTrue();
        assertThat(COMMANDS_USE_NO_CLOCK.evaluate(instantNow).hasViolation())
                .as("Instant.now() alone is caught by its own rule").isFalse();
    }

    @Test
    void aCommandThatLeavesTransactionAndTimeToTheRunnerPasses() {
        JavaClasses clean = new ClassFileImporter().importClasses(PlainCommand.class);

        ALL_RULES.forEach(rule -> assertThat(rule.evaluate(clean).hasViolation()).as(rule.getDescription()).isFalse());
    }

    @Test
    void infrastructureCodeThatIsNotACommandIsNotRestricted() {
        JavaClasses infrastructure = new ClassFileImporter()
                .importClasses(InfrastructureTransactionWorker.class, InfrastructureClockWorker.class);

        ALL_RULES.forEach(rule ->
                assertThat(rule.evaluate(infrastructure).hasViolation()).as(rule.getDescription()).isFalse());
    }
}
