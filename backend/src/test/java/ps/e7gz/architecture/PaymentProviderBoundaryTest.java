package ps.e7gz.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import ps.e7gz.ports.PaymentProvider;

/**
 * The NFR-38 boundary (arch 11 section 7, arch 03 section 18, ADR-018; E7-66, PO decision 23 Oct 2026): a non-zero
 * Booking Fee must need no redesign, so the booking path never depends on a payment integration.
 */
@AnalyzeClasses(packages = "ps.e7gz", importOptions = ImportOption.DoNotIncludeTests.class)
class PaymentProviderBoundaryTest {

    /** NFR-38: nothing under booking, loyalty or tournament imports the port - now or after payments ship. */
    @ArchTest
    static final ArchRule booking_loyalty_and_tournament_never_use_the_payment_provider =
            noClasses().that().resideInAnyPackage("ps.e7gz.booking..", "ps.e7gz.loyalty..", "ps.e7gz.tournament..")
                    .should().dependOnClassesThat(belongToAnyOf(PaymentProvider.class))
                    .as("nothing under booking, loyalty or tournament uses PaymentProvider (NFR-38)");

    /**
     * ADR-018: at launch the port has no implementation and no caller. The task that ships payment collection replaces
     * this rule with its adapter; the rule above stays.
     */
    @ArchTest
    static final ArchRule the_payment_provider_has_no_implementation_and_no_caller_at_launch =
            noClasses().that().doNotBelongToAnyOf(PaymentProvider.class)
                    .should().dependOnClassesThat(belongToAnyOf(PaymentProvider.class))
                    .as("PaymentProvider has no implementation and no caller at launch (ADR-018)");
}
