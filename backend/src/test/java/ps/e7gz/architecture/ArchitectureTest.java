package ps.e7gz.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.dependencies.SliceAssignment;
import com.tngtech.archunit.library.dependencies.SliceIdentifier;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Module boundaries (arch 03 §2, DEVELOPER-GUIDE.md §2).
 *
 * <p>A module is the package in front of its {@code api}/{@code internal}/{@code web} layer, e.g.
 * {@code booking}, {@code booking.lateconfirm}, {@code inventory.ledger}. {@code common} and the root
 * package are not modules.
 */
@AnalyzeClasses(packages = "ps.e7gz", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String ROOT = "ps.e7gz";
    private static final Set<String> LAYERS = Set.of("api", "internal", "web");

    /** (a) A module may use another module only through that module's {@code api} package. */
    @ArchTest
    static final ArchRule modules_talk_only_through_api =
            classes().that().resideInAPackage(ROOT + "..")
                    .should(onlyUseOtherModulesThroughTheirApi())
                    .as("a module may use another module only through its api package");

    /** (b) No dependency cycles between modules. */
    @ArchTest
    static final ArchRule no_cycles_between_modules =
            slices().assignedFrom(new ModuleSlices()).should().beFreeOfCycles();

    /** (c) {@code web} is HTTP only: nothing in {@code api} or {@code internal} may depend on it. */
    @ArchTest
    static final ArchRule web_is_not_used_by_api_or_internal =
            noClasses().that().resideInAnyPackage("..api..", "..internal..")
                    .should().dependOnClassesThat().resideInAPackage("..web..");

    /** (d) The shared kernel never depends on a module. */
    @ArchTest
    static final ArchRule common_does_not_depend_on_modules =
            noClasses().that().resideInAPackage(ROOT + ".common..")
                    .should().dependOnClassesThat(
                            resideInAPackage(ROOT + "..").and(not(resideInAPackage(ROOT + ".common.."))));

    private static ArchCondition<JavaClass> onlyUseOtherModulesThroughTheirApi() {
        return new ArchCondition<>("only use other modules through their api package") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                Optional<ModuleRef> from = ModuleRef.of(origin.getPackageName());
                if (from.isEmpty()) {
                    return;
                }
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    Optional<ModuleRef> to = ModuleRef.of(dependency.getTargetClass().getPackageName());
                    if (to.isPresent()
                            && !to.get().module().equals(from.get().module())
                            && !"api".equals(to.get().layer())) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        };
    }

    /** Module name and layer ({@code api}/{@code internal}/{@code web}, or empty for the module root). */
    private record ModuleRef(String module, String layer) {

        static Optional<ModuleRef> of(String packageName) {
            if (!packageName.startsWith(ROOT + ".")) {
                return Optional.empty();
            }
            List<String> parts = List.of(packageName.substring(ROOT.length() + 1).split("\\."));
            if (parts.get(0).equals("common")) {
                return Optional.empty();
            }
            for (int i = 0; i < parts.size(); i++) {
                if (LAYERS.contains(parts.get(i))) {
                    return Optional.of(new ModuleRef(String.join(".", parts.subList(0, i)), parts.get(i)));
                }
            }
            return Optional.of(new ModuleRef(packageName.substring(ROOT.length() + 1), ""));
        }
    }

    private static final class ModuleSlices implements SliceAssignment {

        @Override
        public SliceIdentifier getIdentifierOf(JavaClass javaClass) {
            return ModuleRef.of(javaClass.getPackageName())
                    .map(ref -> SliceIdentifier.of(ref.module()))
                    .orElse(SliceIdentifier.ignore());
        }

        @Override
        public String getDescription() {
            return "E7GZ modules";
        }
    }
}
