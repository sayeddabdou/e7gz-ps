package ps.e7gz.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noCodeUnits;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ps.e7gz.architecture.timefixture.CallsClockSystemUtc;
import ps.e7gz.architecture.timefixture.CallsCurrentTimeMillis;
import ps.e7gz.architecture.timefixture.CallsInstantNow;
import ps.e7gz.architecture.timefixture.CallsLocalDateTimeNow;
import ps.e7gz.architecture.timefixture.CallsNewDate;
import ps.e7gz.architecture.timefixture.UsesInjectedClock;
import ps.e7gz.architecture.timefixture.internal.BigDecimalFromDouble;
import ps.e7gz.architecture.timefixture.internal.DoubleField;
import ps.e7gz.architecture.timefixture.internal.DoubleParameter;
import ps.e7gz.architecture.timefixture.internal.DoubleReturn;
import ps.e7gz.architecture.timefixture.internal.MoneyAsBigDecimal;
import ps.e7gz.architecture.timefixture.web.CoordinatesDto;

/**
 * The E7-12 ban rules, each shown to fire on a fixture that breaks it:
 * <ul>
 *   <li>time: only {@code ps.e7gz.common.clock} may read the system clock — everything else injects {@link Clock}
 *       (arch 08 §8);</li>
 *   <li>money: no {@code float}/{@code double} in domain packages ({@code api}, {@code internal},
 *       {@code common.money}), and no {@code BigDecimal} built from a {@code double} anywhere (NFR-25, ADR-017).
 *       Web DTOs such as {@code GeoPoint} may carry coordinates as {@code Double}.</li>
 * </ul>
 */
class TimeAndMoneyRulesTest {

    private static final String ROOT = "ps.e7gz";
    private static final String[] DOMAIN = {ROOT + "..api..", ROOT + "..internal..", ROOT + ".common.money.."};
    private static final Set<String> FLOATING = Set.of("double", "float", "java.lang.Double", "java.lang.Float");

    static final ArchRule NO_SYSTEM_TIME_OUTSIDE_THE_CLOCK = noClasses()
            .that().resideInAPackage(ROOT + "..").and().resideOutsideOfPackage(ROOT + ".common.clock..")
            .should().callMethod(Instant.class, "now")
            .orShould().callMethod(LocalDateTime.class, "now")
            .orShould().callMethod(LocalDate.class, "now")
            .orShould().callMethod(LocalTime.class, "now")
            .orShould().callMethod(OffsetDateTime.class, "now")
            .orShould().callMethod(ZonedDateTime.class, "now")
            .orShould().callMethod(System.class, "currentTimeMillis")
            .orShould().callMethod(Clock.class, "systemUTC")
            .orShould().callMethod(Clock.class, "systemDefaultZone")
            .orShould().callConstructor(java.util.Date.class)
            .as("only ps.e7gz.common.clock reads the system clock; inject java.time.Clock (arch 08 §8)");

    static final ArchRule NO_FLOATING_POINT_FIELDS_IN_DOMAIN = noFields()
            .that().areDeclaredInClassesThat().resideInAnyPackage(DOMAIN)
            .should().haveRawType(double.class).orShould().haveRawType(float.class)
            .orShould().haveRawType(Double.class).orShould().haveRawType(Float.class)
            .as("no float/double fields in domain packages (NFR-25)");

    static final ArchRule NO_FLOATING_POINT_SIGNATURES_IN_DOMAIN = noCodeUnits()
            .that().areDeclaredInClassesThat().resideInAnyPackage(DOMAIN)
            .should(useFloatingPointInTheirSignature())
            .as("no float/double parameters or return types in domain packages (NFR-25)");

    static final ArchRule NO_BIG_DECIMAL_FROM_DOUBLE = noClasses()
            .that().resideInAPackage(ROOT + "..")
            .should().callConstructor(BigDecimal.class, double.class)
            .orShould().callMethod(BigDecimal.class, "valueOf", double.class)
            .as("no BigDecimal built from a double (ADR-017)");

    private static final ArchRule[] RULES = {NO_SYSTEM_TIME_OUTSIDE_THE_CLOCK, NO_FLOATING_POINT_FIELDS_IN_DOMAIN,
            NO_FLOATING_POINT_SIGNATURES_IN_DOMAIN, NO_BIG_DECIMAL_FROM_DOUBLE};

    @Test
    void productionCodeFollowsEveryRule() {
        JavaClasses production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages(ROOT);
        for (ArchRule rule : RULES) {
            rule.check(production);
        }
    }

    @ParameterizedTest
    @ValueSource(classes = {CallsInstantNow.class, CallsLocalDateTimeNow.class, CallsCurrentTimeMillis.class,
            CallsClockSystemUtc.class, CallsNewDate.class})
    void theClockRuleFiresOutsideTheClockPackage(Class<?> offender) {
        assertThatThrownBy(() -> NO_SYSTEM_TIME_OUTSIDE_THE_CLOCK.check(importOf(offender)))
                .isInstanceOf(AssertionError.class).hasMessageContaining(offender.getSimpleName());
    }

    @Test
    void anInjectedClockIsAllowed() {
        assertThatCode(() -> NO_SYSTEM_TIME_OUTSIDE_THE_CLOCK.check(importOf(UsesInjectedClock.class)))
                .doesNotThrowAnyException();
    }

    @Test
    void theClockPackageItselfMayReadTheSystemClock() {
        assertThatCode(() -> NO_SYSTEM_TIME_OUTSIDE_THE_CLOCK.check(importOf(ps.e7gz.common.clock.ClockConfig.class)))
                .doesNotThrowAnyException();
    }

    @Test
    void theFloatingPointFieldRuleFiresInADomainPackage() {
        assertThatThrownBy(() -> NO_FLOATING_POINT_FIELDS_IN_DOMAIN.check(importOf(DoubleField.class)))
                .isInstanceOf(AssertionError.class).hasMessageContaining("DoubleField");
    }

    @ParameterizedTest
    @ValueSource(classes = {DoubleParameter.class, DoubleReturn.class})
    void theFloatingPointSignatureRuleFiresInADomainPackage(Class<?> offender) {
        assertThatThrownBy(() -> NO_FLOATING_POINT_SIGNATURES_IN_DOMAIN.check(importOf(offender)))
                .isInstanceOf(AssertionError.class).hasMessageContaining(offender.getSimpleName());
    }

    @Test
    void theBigDecimalFromDoubleRuleFires() {
        assertThatThrownBy(() -> NO_BIG_DECIMAL_FROM_DOUBLE.check(importOf(BigDecimalFromDouble.class)))
                .isInstanceOf(AssertionError.class).hasMessageContaining("BigDecimalFromDouble");
    }

    @Test
    void bigDecimalMoneyInTheDomainAndDoubleCoordinatesInAWebDtoAreAllowed() {
        for (ArchRule rule : RULES) {
            assertThatCode(() -> rule.check(importOf(MoneyAsBigDecimal.class, CoordinatesDto.class)))
                    .doesNotThrowAnyException();
        }
    }

    private static JavaClasses importOf(Class<?>... classes) {
        return new ClassFileImporter().importClasses(classes);
    }

    private static ArchCondition<JavaCodeUnit> useFloatingPointInTheirSignature() {
        return new ArchCondition<>("use float or double in their signature") {
            @Override
            public void check(JavaCodeUnit unit, ConditionEvents events) {
                boolean floating = FLOATING.contains(unit.getRawReturnType().getName())
                        || unit.getRawParameterTypes().stream().map(JavaClass::getName).anyMatch(FLOATING::contains);
                // Compiler-generated members of records/enums with a floating component are covered by the field rule.
                if (floating && !unit.getModifiers().contains(JavaModifier.SYNTHETIC)) {
                    events.add(SimpleConditionEvent.satisfied(unit, unit.getDescription()));
                }
            }
        };
    }
}
