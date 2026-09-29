package ps.e7gz.common.codes;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import ps.e7gz.common.codes.ClosedValueContract.Canonical;

/** No enum of {@code ps.e7gz.common.codes} escapes the parity contract unless it is deliberately derived-only. */
class CodesCoverageTest {

    private static Set<String> productionEnums() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("ps.e7gz.common.codes")
                .stream()
                .filter(JavaClass::isEnum)
                .filter(c -> c.getPackageName().equals("ps.e7gz.common.codes"))
                .map(JavaClass::getName)
                .collect(Collectors.toSet());
    }

    private static Set<String> mapped() {
        return ClosedValueContract.CANONICAL.stream().map(Canonical::type).map(Class::getName).collect(Collectors.toSet());
    }

    private static Set<String> derivedOnly() {
        return ClosedValueContract.DERIVED_ONLY.stream().map(Class::getName).collect(Collectors.toSet());
    }

    @Test
    void everyEnumIsMappedToTheDatabaseOrDeliberatelyDerivedOnly() {
        Set<String> unaccounted = productionEnums();
        unaccounted.removeAll(mapped());
        unaccounted.removeAll(derivedOnly());

        assertThat(unaccounted).as("enums with no DB mapping and no derived-only declaration").isEmpty();
    }

    @Test
    void derivedOnlyEnumsHaveNoDatabaseMapping() {
        assertThat(derivedOnly()).doesNotContainAnyElementsOf(mapped());
    }

    @Test
    void theDerivedOnlyAllowlistIsExactlyScheduleDisplayState() {
        assertThat(ClosedValueContract.DERIVED_ONLY).containsExactly(ScheduleDisplayState.class);
    }

    @Test
    void literalBranchEnumsAreOrdinaryDatabaseBackedEnums() {
        assertThat(mapped()).contains(IdempotencyState.class.getName(), RewardSourceKind.class.getName());
        assertThat(derivedOnly()).doesNotContain(IdempotencyState.class.getName(), RewardSourceKind.class.getName());
    }

    @Test
    void everyMappingPointsAtAnEnumOfThisPackage() {
        assertThat(productionEnums()).containsAll(mapped()).containsAll(derivedOnly());
    }
}
