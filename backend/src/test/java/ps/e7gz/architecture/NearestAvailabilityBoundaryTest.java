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
