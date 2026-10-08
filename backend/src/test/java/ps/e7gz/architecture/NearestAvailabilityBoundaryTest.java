package ps.e7gz.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import ps.e7gz.inventory.availability.api.AvailabilityQuery;
import ps.e7gz.inventory.availability.api.AvailabilitySnapshots;
import ps.e7gz.inventory.availability.api.DiscoveryRatings;
import ps.e7gz.inventory.availability.api.NearestAvailabilityProjection;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.operations.api.OperationalOverlaps;
import ps.e7gz.resource.api.ResourceStates;
import ps.e7gz.venue.api.VenueSellingBlocks;

/**
 * E7-33 - the Nearest Availability projection stays advisory and derived (arch 05 section 5.3, {@code FR-AVL-11}):
 * <ul>
 *   <li>no commit path reads it - only its store names the table, and the commit-path modules and the canonical
 *       predicate never depend on it;</li>
 *   <li>it is not a second predicate - the calculation asks {@link AvailabilityQuery} and does not touch the owner reads
 *       that exist for the eight checks ({@code AvailabilityPredicateSingularityTest} also forbids calling them).</li>
 * </ul>
 */
class NearestAvailabilityBoundaryTest {

    private static final String ROOT = "ps.e7gz";
    private static final String AVAILABILITY_INTERNAL = ROOT + ".inventory.availability.internal.";
    static final String CALCULATOR = AVAILABILITY_INTERNAL + "NearestAvailabilityCalculator";
    static final String STORE = "JdbcAvailabilityProjectionStore.java";

    /** The table used in SQL; Javadoc may still name it. */
    private static final Pattern TABLE =
            Pattern.compile("(?i)\\b(FROM|INTO|UPDATE|JOIN)\\s+inventory\\.venue_activity_availability\\b");

    private static final ProductionClasses PRODUCTION = new ProductionClasses();

    /** Imported once, production classes only. */
    static final class ProductionClasses {

        private JavaClasses classes;

        synchronized JavaClasses get() {
            if (classes == null) {
                classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages(ROOT);
            }
            return classes;
        }
    }

    static final ArchRule COMMIT_PATHS_NEVER_DEPEND_ON_THE_PROJECTION = noClasses()
            .that().resideInAnyPackage(ROOT + ".booking..", ROOT + ".tournament..", ROOT + ".operations..",
                    ROOT + ".attendance..", ROOT + ".inventory.ledger..")
            .or().haveFullyQualifiedName(AVAILABILITY_INTERNAL + "CanonicalAvailabilityQuery")
            .should().dependOnClassesThat().areAssignableTo(NearestAvailabilityProjection.class)
            .orShould().dependOnClassesThat().areAssignableTo(AvailabilitySnapshots.class)
            .orShould().dependOnClassesThat().haveNameMatching(".*AvailabilityProjection.*")
            .as("booking, tournament, operations, attendance, the ledger and isFree never read the projection");

    static final ArchRule THE_CALCULATION_IS_NOT_A_SECOND_PREDICATE = noClasses()
            .that().haveFullyQualifiedName(CALCULATOR)
            .should().dependOnClassesThat().areAssignableTo(VenueSellingBlocks.class)
            .orShould().dependOnClassesThat().areAssignableTo(ResourceStates.class)
            .orShould().dependOnClassesThat().areAssignableTo(OperationalOverlaps.class)
            .orShould().dependOnClassesThat().areAssignableTo(ReservationLedger.class)
            .as("the Nearest Availability calculation uses none of the eight checks' owner reads");

    static final ArchRule THE_CALCULATION_ASKS_THE_CANONICAL_PREDICATE = classes()
            .that().haveFullyQualifiedName(CALCULATOR)
            .should().dependOnClassesThat().areAssignableTo(AvailabilityQuery.class)
            .allowEmptyShould(false)
            .as("the Nearest Availability calculation exists and asks AvailabilityQuery");

    /** E7-34: the customer discovery request path and its HTTP controller. */
    static final String[] DISCOVERY = {AVAILABILITY_INTERNAL + "CustomerDiscovery",
            ROOT + ".inventory.availability.web.CustomerDiscoveryController"};

    static final ArchRule DISCOVERY_NEVER_ASKS_THE_PREDICATE_OR_REFRESHES = noClasses()
            .that().haveFullyQualifiedName(DISCOVERY[0]).or().haveFullyQualifiedName(DISCOVERY[1])
            .should().dependOnClassesThat().areAssignableTo(AvailabilityQuery.class)
            .orShould().dependOnClassesThat().areAssignableTo(NearestAvailabilityProjection.class)
            .orShould().dependOnClassesThat().areAssignableTo(ReservationLedger.class)
            .orShould().dependOnClassesThat().haveSimpleName("JdbcAvailabilityProjectionStore")
            .as("discovery neither asks isFree nor refreshes or rebuilds the projection (E7-34, FR-AVL-11)");

    static final ArchRule DISCOVERY_READS_THE_SNAPSHOTS = classes()
            .that().haveFullyQualifiedName(DISCOVERY[0])
            .should().dependOnClassesThat().areAssignableTo(AvailabilitySnapshots.class)
            .allowEmptyShould(false)
            .as("discovery reads Nearest Availability and starting price through AvailabilitySnapshots");

    /** FR-DIS-12, arch 05 section 5.4: Find Available, and the command class nested in it. */
    static final String FIND_AVAILABLE = AVAILABILITY_INTERNAL + "FindAvailable";

    static final ArchRule FIND_AVAILABLE_ASKS_THE_CANONICAL_PREDICATE = classes()
            .that().haveFullyQualifiedName(FIND_AVAILABLE)
            .should().dependOnClassesThat().areAssignableTo(AvailabilityQuery.class)
            .allowEmptyShould(false)
            .as("Find Available exists and asks AvailabilityQuery live (FR-DIS-12, arch 05 section 5.4)");

    static final ArchRule FIND_AVAILABLE_IS_LIVE_AND_NOT_A_SECOND_PREDICATE = noClasses()
            .that().haveNameMatching(Pattern.quote(FIND_AVAILABLE) + "(\\$.*)?")
            .should().dependOnClassesThat().areAssignableTo(AvailabilitySnapshots.class)
            .orShould().dependOnClassesThat().areAssignableTo(NearestAvailabilityProjection.class)
            .orShould().dependOnClassesThat().areAssignableTo(VenueSellingBlocks.class)
            .orShould().dependOnClassesThat().areAssignableTo(ResourceStates.class)
            .orShould().dependOnClassesThat().areAssignableTo(OperationalOverlaps.class)
            .orShould().dependOnClassesThat().areAssignableTo(ReservationLedger.class)
            .as("Find Available reads neither the projection nor the eight checks' owner reads");

    @Test
    void findAvailableAsksTheCanonicalPredicateLive() {
        FIND_AVAILABLE_ASKS_THE_CANONICAL_PREDICATE.check(PRODUCTION.get());
        FIND_AVAILABLE_IS_LIVE_AND_NOT_A_SECOND_PREDICATE.check(PRODUCTION.get());
    }

    /**
     * E7-34, arch 03 section 2.1: Availability (layer 2) composes discovery but never depends upward on Reviews (layer
     * 4). Ratings arrive through the {@link DiscoveryRatings} port Availability declares and Reviews implements.
     */
    static final ArchRule AVAILABILITY_NEVER_DEPENDS_ON_REVIEWS = noClasses()
            .that().resideInAPackage(ROOT + ".inventory.availability..")
            .should().dependOnClassesThat().resideInAPackage(ROOT + ".review..")
            .as("Availability never depends upward on Reviews (arch 03 section 2.1, E7-34)");

    static final ArchRule THE_RATING_PORT_IS_IMPLEMENTED_BY_REVIEWS = classes()
            .that().implement(DiscoveryRatings.class)
            .should().resideInAPackage(ROOT + ".review.internal")
            .allowEmptyShould(false)
            .as("the discovery rating port is implemented by the Reviews module, which owns venue_rating_stats");

    @Test
    void discoveryReadsRatingsWithoutAnUpwardDependency() {
        AVAILABILITY_NEVER_DEPENDS_ON_REVIEWS.check(PRODUCTION.get());
        THE_RATING_PORT_IS_IMPLEMENTED_BY_REVIEWS.check(PRODUCTION.get());
    }

    @Test
    void discoveryIsAnAdvisoryReadOfTheProjection() {
        DISCOVERY_READS_THE_SNAPSHOTS.check(PRODUCTION.get());
        DISCOVERY_NEVER_ASKS_THE_PREDICATE_OR_REFRESHES.check(PRODUCTION.get());
    }

    @Test
    void noCommitPathDependsOnTheProjection() {
        COMMIT_PATHS_NEVER_DEPEND_ON_THE_PROJECTION.check(PRODUCTION.get());
    }

    @Test
    void theCalculationAsksTheCanonicalPredicateAndRebuildsNoCheck() {
        THE_CALCULATION_ASKS_THE_CANONICAL_PREDICATE.check(PRODUCTION.get());
        THE_CALCULATION_IS_NOT_A_SECOND_PREDICATE.check(PRODUCTION.get());
    }

    @Test
    void onlyTheProjectionStoreHasSqlOnTheProjectionTable() throws IOException {
        Path sources = Path.of("src/main/java");
        List<Path> naming;
        try (Stream<Path> files = Files.walk(sources)) {
            naming = files.filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> TABLE.matcher(read(file)).find())
                    .toList();
        }

        assertThat(naming).extracting(file -> file.getFileName().toString())
                .as("Java sources with SQL on inventory.venue_activity_availability").containsExactly(STORE);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
