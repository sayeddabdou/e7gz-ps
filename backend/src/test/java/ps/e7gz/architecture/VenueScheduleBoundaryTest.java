package ps.e7gz.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import ps.e7gz.inventory.availability.api.AvailabilityQuery;
import ps.e7gz.inventory.availability.api.NearestAvailabilityProjection;
import ps.e7gz.inventory.availability.api.ScheduleBookings;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.operations.api.OperationalOverlaps;
import ps.e7gz.resource.api.ResourceStates;
import ps.e7gz.venue.api.VenueSellingBlocks;

/**
 * E7-112 - the Venue Dashboard schedule (FR-VEN-37) is a read of the owner modules' rows, not a second availability
 * predicate, and Availability reaches the Booking facts it needs without depending upward on Booking (arch 03 section
 * 2.1).
 */
class VenueScheduleBoundaryTest {

    private static final String ROOT = "ps.e7gz";
    private static final String[] SCHEDULE = {ROOT + ".inventory.availability.internal.VenueSchedules",
            ROOT + ".inventory.availability.web.VenueAvailabilityController"};

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages(ROOT);

    static final ArchRule THE_SCHEDULE_IS_NOT_A_PREDICATE = noClasses()
            .that().haveFullyQualifiedName(SCHEDULE[0]).or().haveFullyQualifiedName(SCHEDULE[1])
            .should().dependOnClassesThat().areAssignableTo(AvailabilityQuery.class)
            .orShould().dependOnClassesThat().areAssignableTo(OperationalOverlaps.class)
            .orShould().dependOnClassesThat().areAssignableTo(ReservationLedger.class)
            .orShould().dependOnClassesThat().areAssignableTo(VenueSellingBlocks.class)
            .orShould().dependOnClassesThat().areAssignableTo(ResourceStates.class)
            .orShould().dependOnClassesThat().areAssignableTo(NearestAvailabilityProjection.class)
            .as("the schedule neither asks isFree nor uses the eight checks' owner reads or the projection");

    static final ArchRule THE_SCHEDULE_EXISTS = classes()
            .that().haveFullyQualifiedName(SCHEDULE[0])
            .should().dependOnClassesThat().areAssignableTo(ScheduleBookings.class)
            .allowEmptyShould(false)
            .as("the schedule reads Booking facts through the ScheduleBookings port");

    static final ArchRule AVAILABILITY_NEVER_DEPENDS_ON_BOOKING = noClasses()
            .that().resideInAPackage(ROOT + ".inventory.availability..")
            .should().dependOnClassesThat().resideInAPackage(ROOT + ".booking..")
            .as("Availability never depends upward on Booking (arch 03 section 2.1, E7-112)");

    static final ArchRule THE_BOOKING_PORT_IS_IMPLEMENTED_BY_BOOKING = classes()
            .that().implement(ScheduleBookings.class)
            .should().resideInAPackage(ROOT + ".booking.internal")
            .allowEmptyShould(false)
            .as("the schedule's Booking port is implemented by the Booking module, which owns booking.booking");

    @Test
    void theScheduleIsAReadNotAPredicate() {
        THE_SCHEDULE_EXISTS.check(PRODUCTION);
        THE_SCHEDULE_IS_NOT_A_PREDICATE.check(PRODUCTION);
    }

    @Test
    void bookingFactsArriveWithoutAnUpwardDependency() {
        AVAILABILITY_NEVER_DEPENDS_ON_BOOKING.check(PRODUCTION);
        THE_BOOKING_PORT_IS_IMPLEMENTED_BY_BOOKING.check(PRODUCTION);
    }
}
