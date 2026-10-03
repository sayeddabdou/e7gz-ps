package ps.e7gz.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget.MethodCallTarget;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import ps.e7gz.architecture.predicatefixture.AnotherAvailabilityQuery;
import ps.e7gz.architecture.predicatefixture.AsksTheCanonicalQuery;
import ps.e7gz.architecture.predicatefixture.RebuildsTheEightChecks;
import ps.e7gz.inventory.availability.api.AvailabilityCheck;
import ps.e7gz.inventory.availability.api.AvailabilityQuery;
import ps.e7gz.inventory.availability.api.PredicateResult;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.operations.api.OperationalOverlaps;
import ps.e7gz.resource.api.ResourceStates;
import ps.e7gz.venue.api.VenueSellingBlocks;

/**
 * E7-30 - predicate singularity (arch 01 section 15 rule 1): exactly one class implements {@code FR-AVL-02}'s eight
 * ordered checks. Every other flow (Booking, Late Confirm, Tournament, Operations) asks {@link AvailabilityQuery}; it may
 * not call the check reads the owner modules publish for the predicate, nor decide a {@link PredicateResult#notFree}
 * itself. The owner modules' own adapters implement those reads; implementing is not calling.
 *
 * <p>Each rule is shown to fire on a fixture that breaks it, and the canonical class is required to exist and to call
 * every check read, so no rule can pass by matching nothing.
 */
class AvailabilityPredicateSingularityTest {

    private static final String ROOT = "ps.e7gz";
    static final String CANONICAL = "ps.e7gz.inventory.availability.internal.CanonicalAvailabilityQuery";

    /** The owner reads that exist only for the eight checks: port, method names. */
    private static final List<CheckRead> CHECK_READS = List.of(
            new CheckRead(VenueSellingBlocks.class, Set.of("venueBlocked")),
            new CheckRead(ResourceStates.class, Set.of("resourceBlocked")),
            new CheckRead(OperationalOverlaps.class,
                    Set.of("declaredEmergencyOverlaps", "maintenanceOverlaps", "closureOverlaps")),
            new CheckRead(ReservationLedger.class, Set.of("hasBlockingOverlap")));

    record CheckRead(Class<?> port, Set<String> methods) {

        boolean matches(MethodCallTarget target) {
            return methods.contains(target.getName()) && target.getOwner().isAssignableTo(port);
        }
    }

    private static final DescribedPredicate<JavaMethodCall> A_CHECK_READ =
            new DescribedPredicate<>("a read the owner modules publish for the eight availability checks") {
                @Override
                public boolean test(JavaMethodCall call) {
                    return CHECK_READS.stream().anyMatch(read -> read.matches(call.getTarget()));
                }
            };

    static final ArchRule ONE_IMPLEMENTATION = classes()
            .that().implement(AvailabilityQuery.class)
            .should().haveFullyQualifiedName(CANONICAL)
            .allowEmptyShould(false)
            .as("AvailabilityQuery has exactly one implementation, " + CANONICAL + " (arch 01 section 15 rule 1)");

    static final ArchRule ONLY_THE_CANONICAL_QUERY_READS_THE_CHECKS = noClasses()
            .that().resideInAPackage(ROOT + "..").and().doNotHaveFullyQualifiedName(CANONICAL)
            .should().callMethodWhere(A_CHECK_READ)
            .as("only " + CANONICAL + " calls the availability check reads; other flows ask AvailabilityQuery");

    static final ArchRule ONLY_THE_CANONICAL_QUERY_DECIDES_NOT_FREE = noClasses()
            .that().resideInAPackage(ROOT + "..").and().doNotHaveFullyQualifiedName(CANONICAL)
            .should().callMethod(PredicateResult.class, "notFree", AvailabilityCheck.class)
            .as("only " + CANONICAL + " decides which availability check failed");

    static final ArchRule THE_CANONICAL_QUERY_READS_EVERY_CHECK = classes()
            .that().haveFullyQualifiedName(CANONICAL)
            .should(callEveryCheckRead())
            .allowEmptyShould(false)
            .as(CANONICAL + " exists and calls every availability check read");

    private static final ArchRule[] RULES = {ONE_IMPLEMENTATION, ONLY_THE_CANONICAL_QUERY_READS_THE_CHECKS,
            ONLY_THE_CANONICAL_QUERY_DECIDES_NOT_FREE, THE_CANONICAL_QUERY_READS_EVERY_CHECK};

    @Test
    void productionHasExactlyOneEightCheckPredicate() {
        JavaClasses production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages(ROOT);

        assertThat(production.stream()
                .filter(c -> !c.isInterface() && c.isAssignableTo(AvailabilityQuery.class))
                .map(JavaClass::getName))
                .as("concrete AvailabilityQuery classes").containsExactly(CANONICAL);
        for (ArchRule rule : RULES) {
            rule.check(production);
        }
    }

    @Test
    void aSecondImplementationIsRefused() {
        assertThatThrownBy(() -> ONE_IMPLEMENTATION.check(importOf(AnotherAvailabilityQuery.class)))
                .isInstanceOf(AssertionError.class).hasMessageContaining("AnotherAvailabilityQuery");
    }

    @Test
    void rebuildingTheChecksFromTheOwnerReadsIsRefused() {
        assertThatThrownBy(() -> ONLY_THE_CANONICAL_QUERY_READS_THE_CHECKS.check(
                importOf(RebuildsTheEightChecks.class)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("RebuildsTheEightChecks")
                .hasMessageContaining("venueBlocked")
                .hasMessageContaining("resourceBlocked")
                .hasMessageContaining("maintenanceOverlaps")
                .hasMessageContaining("hasBlockingOverlap");
    }

    @Test
    void decidingANotFreeResultElsewhereIsRefused() {
        assertThatThrownBy(() -> ONLY_THE_CANONICAL_QUERY_DECIDES_NOT_FREE.check(
                importOf(AnotherAvailabilityQuery.class)))
                .isInstanceOf(AssertionError.class).hasMessageContaining("AnotherAvailabilityQuery");
    }

    @Test
    void aMissingCanonicalQueryIsRefused() {
        assertThatThrownBy(() -> THE_CANONICAL_QUERY_READS_EVERY_CHECK.check(importOf(AsksTheCanonicalQuery.class)))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void aFlowThatAsksTheCanonicalQueryIsAllowed() {
        JavaClasses flow = importOf(AsksTheCanonicalQuery.class);

        assertThatCode(() -> ONLY_THE_CANONICAL_QUERY_READS_THE_CHECKS.check(flow)).doesNotThrowAnyException();
        assertThatCode(() -> ONLY_THE_CANONICAL_QUERY_DECIDES_NOT_FREE.check(flow)).doesNotThrowAnyException();
    }

    private static JavaClasses importOf(Class<?>... classes) {
        return new ClassFileImporter().importClasses(classes);
    }

    private static ArchCondition<JavaClass> callEveryCheckRead() {
        return new ArchCondition<>("call every availability check read") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                Set<String> called = javaClass.getMethodCallsFromSelf().stream()
                        .filter(A_CHECK_READ)
                        .map(call -> call.getTarget().getName())
                        .collect(Collectors.toSet());
                Set<String> expected = CHECK_READS.stream().flatMap(read -> read.methods().stream())
                        .collect(Collectors.toSet());
                boolean all = called.containsAll(expected);
                events.add(new SimpleConditionEvent(javaClass, all,
                        javaClass.getName() + (all ? " calls " : " does not call all of ") + expected));
            }
        };
    }
}
