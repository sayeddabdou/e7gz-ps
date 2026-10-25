package ps.e7gz.booking.internal;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

/**
 * E7-114 (PO decision 12 Oct 2026, Sprint 3 review): the venue's cancellation of a CONFIRMED booking creates no
 * notification itself - it publishes {@code BookingCancelled}, and E7-55's consumer is the one place the customer's
 * notification is made. Nothing of the cancellation reaches the notification module, {@code Notifications} included.
 */
class VenueConfirmedCancellationArchitectureTest {

    @Test
    void theCancellationNeverCallsTheNotificationModule() {
        JavaClasses cancellation = new ClassFileImporter().importClasses(VenueConfirmedCancellation.class,
                VenueConfirmedCancellations.class, VenueConfirmedCancellations.ProtectedCancel.class,
                VenueConfirmedCancellations.Protected.class);

        noClasses().should().dependOnClassesThat().resideInAPackage("ps.e7gz.notification..")
                .because("the customer notification is E7-55's consumer's, from BookingCancelled")
                .check(cancellation);
    }
}
