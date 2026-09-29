package ps.e7gz.audit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
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
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ps.e7gz.audit.api.AuditAction;
import ps.e7gz.audit.api.AuditWriter;
import ps.e7gz.audit.archfixture.BypassingAuditWriter;
import ps.e7gz.audit.archfixture.FreeTextAuditAction;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.support.RepoFiles;

/**
 * The audit writer is append-only, runs on the command's transaction and clock, never creates a correlation ID, and
 * actions are typed constants (E7-17).
 */
class AuditArchitectureTest {

    private static final String AUDIT = "ps.e7gz.audit..";

    private static final Set<String> TRANSACTIONAL =
            Set.of("org.springframework.transaction.annotation.Transactional", "jakarta.transaction.Transactional");

    /** Anything that opens a transaction or reaches a connection other than the command's own. */
    private static final Set<String> OWN_TRANSACTION_OR_CONNECTION = Set.of(
            "org.springframework.transaction.TransactionManager",
            "org.springframework.transaction.PlatformTransactionManager",
            "org.springframework.transaction.support.TransactionTemplate",
            "org.springframework.transaction.support.TransactionOperations",
            "org.springframework.jdbc.datasource.DataSourceTransactionManager",
            "org.springframework.jdbc.support.JdbcTransactionManager",
            "org.springframework.jdbc.datasource.DataSourceUtils",
            "org.springframework.jdbc.core.JdbcTemplate",
            "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate",
            "javax.sql.DataSource",
            "java.sql.Connection",
            "java.sql.DriverManager");

    static final ArchRule NO_TRANSACTIONAL = noClasses().that().resideInAPackage(AUDIT)
            .should().beAnnotatedWith(DescribedPredicate.describe("@Transactional",
                    (JavaAnnotation<?> a) -> TRANSACTIONAL.contains(a.getRawType().getName())))
            .orShould().dependOnClassesThat(DescribedPredicate.describe("@Transactional types",
                    (JavaClass c) -> TRANSACTIONAL.contains(c.getName())))
            .as("the audit writer never declares a transaction");

    static final ArchRule NO_OWN_TRANSACTION_OR_CONNECTION = noClasses().that().resideInAPackage(AUDIT)
            .should().dependOnClassesThat(DescribedPredicate.describe("a transaction manager, template or connection",
                    (JavaClass c) -> OWN_TRANSACTION_OR_CONNECTION.contains(c.getName())))
            .as("the audit writer uses only CommandContext.jdbc()");

    static final ArchRule NO_CLOCK = noClasses().that().resideInAPackage(AUDIT)
            .should().dependOnClassesThat().areAssignableTo(Clock.class)
            .orShould().callMethod(Instant.class, "now")
            .orShould().callMethod(LocalDateTime.class, "now")
            .orShould().callMethod(OffsetDateTime.class, "now")
            .orShould().callMethod(ZonedDateTime.class, "now")
            .orShould().callMethod(LocalDate.class, "now")
            .orShould().callMethod(System.class, "currentTimeMillis")
            .orShould().callMethod(System.class, "nanoTime")
            .as("occurred_at is CommandContext.commandNow(), never a clock");

    static final ArchRule NO_CORRELATION_MECHANISM = noClasses().that().resideInAPackage(AUDIT)
            .should().dependOnClassesThat().areAssignableTo(CorrelationId.class)
            .as("the correlation ID is passed in by the command edge; audit never creates or reads one");

    static final ArchRule NO_MUTATING_METHODS = noMethods().that().areDeclaredInClassesThat().resideInAPackage(AUDIT)
            .should().haveNameMatching("(?i)(update|delete|remove|truncate|erase|purge).*")
            .as("the audit trail is append-only");

    static final ArchRule ACTIONS_ARE_ENUMS = classes().that().areAssignableTo(AuditAction.class)
            .and().areNotInterfaces()
            .should().beEnums()
            .as("audit actions are enum constants, never free text");

    private static final List<ArchRule> WRITER_RULES =
            List.of(NO_TRANSACTIONAL, NO_OWN_TRANSACTION_OR_CONNECTION, NO_CLOCK, NO_CORRELATION_MECHANISM,
                    NO_MUTATING_METHODS);

    private static JavaClasses production;

    @BeforeAll
    static void importProduction() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("ps.e7gz");
    }

    @Test
    void theProductionAuditModuleFollowsEveryRule() {
        WRITER_RULES.forEach(rule -> rule.check(production));
        ACTIONS_ARE_ENUMS.check(production);
    }

    @Test
    void theRulesCatchAWriterThatBypassesTheCommand() {
        // The fixture sits in ps.e7gz.audit.archfixture (test code), so the very same rules apply to it.
        JavaClasses bypass = new ClassFileImporter().importClasses(BypassingAuditWriter.class);
        for (ArchRule rule : WRITER_RULES) {
            assertThat(rule.evaluate(bypass).hasViolation()).as(rule.getDescription()).isTrue();
        }
    }

    @Test
    void theRuleCatchesAFreeTextAction() {
        assertThat(ACTIONS_ARE_ENUMS.evaluate(new ClassFileImporter().importClasses(FreeTextAuditAction.class,
                TestAuditAction.class)).hasViolation()).isTrue();
        assertThat(ACTIONS_ARE_ENUMS.evaluate(new ClassFileImporter().importClasses(TestAuditAction.class))
                .hasViolation()).isFalse();
    }

    @Test
    void theWriterApiHasOnlyWrite() {
        assertThat(Arrays.stream(AuditWriter.class.getMethods()).map(Method::getName)).containsExactly("write");
    }

    @Test
    void theAuditSourcesContainNoUpdateDeleteOrTruncateStatement() throws IOException {
        Pattern mutation = Pattern.compile("\\b(UPDATE|DELETE|TRUNCATE)\\b");
        Path sources = RepoFiles.get("backend/src/main/java/ps/e7gz/audit");
        try (Stream<Path> files = Files.walk(sources)) {
            List<Path> offending = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            return mutation.matcher(Files.readString(p)).find();
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .toList();
            assertThat(offending).isEmpty();
        }
        assertThat(Files.readString(sources.resolve("internal/JdbcAuditWriter.java"))).contains("INSERT INTO audit.audit_event");
    }
}
