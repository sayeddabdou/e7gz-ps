package ps.e7gz.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.security.owner.OwnerPasswordNotRequired;
import ps.e7gz.common.security.owner.OwnerProtected;
import ps.e7gz.support.RepoFiles;
import ps.e7gz.venue.api.OwnerPasswordGate;

/**
 * The Owner Password lists cannot drift (ADR-011, arch 10 §3.3, risk I3). The matrix
 * ({@code architecture/owner-password-actions.tsv}) is FRS §2.4 as data, plus the PCRs and decisions that add to it:
 * <ol>
 *   <li>the FRS §2.4 rows still say exactly what the matrix says, item for item (and PCR-005 still adds the photos);</li>
 *   <li>every {@code @OwnerProtected} command names a PROTECTED action and every {@code @OwnerPasswordNotRequired}
 *       command a NOT_REQUIRED one - so no command in the unprotected list carries the protection;</li>
 *   <li>a built action has its annotated command and an unbuilt one has none - so no protected action can be built
 *       without the annotation, and no annotated command exists in neither list;</li>
 *   <li>a command that checks the Owner Password is annotated {@code @OwnerProtected}.</li>
 * </ol>
 */
class OwnerPasswordMatrixTest {

    private static final String FRS =
            "docs/01-product/E7GZ PS — Functional Requirements Specification (FRS) v1.1 — APPROVED FOR BUILD.md";
    private static final String PCR_005 = "docs/01-product/PCR-005 — Venue Photos from the Venue Dashboard.md";
    private static final String FRS_SOURCE = "FRS §2.4";

    private static Map<String, Row> matrix;
    private static JavaClasses classes;

    record Row(String id, String rule, String source, String phrase, boolean built) {
    }

    @BeforeAll
    static void load() throws IOException {
        matrix = readMatrix();
        classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("ps.e7gz");
    }

    @Test
    void theMatrixMatchesFrsSection24ItemForItem() throws IOException {
        String frs = Files.readString(RepoFiles.get(FRS), StandardCharsets.UTF_8);
        assertThat(phrases("PROTECTED")).as("FRS §2.4 'Owner Password required'")
                .isEqualTo(frsItems(frs, "| Owner Password required |"));
        assertThat(phrases("NOT_REQUIRED")).as("FRS §2.4 'Owner Password NOT required'")
                .isEqualTo(frsItems(frs, "| Owner Password NOT required |"));
    }

    @Test
    void pcr005StillAddsThePhotoActions() throws IOException {
        String pcr = Files.readString(RepoFiles.get(PCR_005), StandardCharsets.UTF_8);
        matrix.values().stream().filter(row -> row.source().startsWith("PCR-005") && !"-".equals(row.phrase()))
                .forEach(row -> assertThat(pcr).as(row.id()).contains(row.phrase()));
    }

    @Test
    void annotationsNameActionsOfTheirOwnRule() {
        List<String> wrong = new ArrayList<>();
        annotated().forEach((javaClass, use) -> {
            Row row = matrix.get(use.id());
            if (row == null) {
                wrong.add(javaClass.getName() + " names " + use.id() + ", which is not in the matrix");
            } else if (!row.rule().equals(use.rule())) {
                wrong.add(javaClass.getName() + " is " + use.rule() + " but " + use.id() + " is " + row.rule());
            }
            if (!javaClass.isAssignableTo(Command.class)) {
                wrong.add(javaClass.getName() + " is annotated but is not a Command");
            }
        });
        classes.stream().filter(c -> c.isAnnotatedWith(OwnerProtected.class)
                        && c.isAnnotatedWith(OwnerPasswordNotRequired.class))
                .forEach(c -> wrong.add(c.getName() + " carries both annotations"));
        assertThat(wrong).isEmpty();
    }

    @Test
    void builtActionsAndAnnotatedCommandsAgree() {
        Set<String> annotatedIds = annotated().values().stream().map(Use::id).collect(Collectors.toCollection(TreeSet::new));
        Set<String> builtIds = matrix.values().stream().filter(Row::built).map(Row::id)
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(annotatedIds).as("ids with an annotated command = rows marked built=yes").isEqualTo(builtIds);
    }

    @Test
    void everyCommandThatChecksTheOwnerPasswordIsOwnerProtected() {
        List<String> unmarked = classes.stream()
                .filter(c -> c.isAssignableTo(Command.class))
                .filter(c -> c.getDirectDependenciesFromSelf().stream()
                        .anyMatch(d -> d.getTargetClass().isEquivalentTo(OwnerPasswordGate.class)))
                .filter(c -> !c.isAnnotatedWith(OwnerProtected.class))
                .map(JavaClass::getName).toList();
        assertThat(unmarked).as("commands using OwnerPasswordGate without @OwnerProtected").isEmpty();
    }

    record Use(String id, String rule) {
    }

    private static Map<JavaClass, Use> annotated() {
        Map<JavaClass, Use> uses = new LinkedHashMap<>();
        for (JavaClass c : classes) {
            if (c.isAnnotatedWith(OwnerProtected.class)) {
                uses.put(c, new Use(c.getAnnotationOfType(OwnerProtected.class).value(), "PROTECTED"));
            } else if (c.isAnnotatedWith(OwnerPasswordNotRequired.class)) {
                uses.put(c, new Use(c.getAnnotationOfType(OwnerPasswordNotRequired.class).value(), "NOT_REQUIRED"));
            }
        }
        return uses;
    }

    private static Set<String> phrases(String rule) {
        return matrix.values().stream().filter(row -> row.rule().equals(rule) && row.source().equals(FRS_SOURCE))
                .map(Row::phrase).collect(Collectors.toCollection(TreeSet::new));
    }

    /** The items of one FRS §2.4 table row: the second cell, split on "; ". */
    private static Set<String> frsItems(String frs, String rowStart) {
        int section = frs.indexOf("## 2.4 Owner Password authorization");
        assertThat(section).as("FRS §2.4 heading").isNotNegative();
        String line = frs.substring(section).lines().filter(l -> l.startsWith(rowStart)).findFirst()
                .orElseThrow(() -> new AssertionError("FRS §2.4 row not found: " + rowStart));
        String cell = line.substring(rowStart.length(), line.lastIndexOf('|')).strip();
        return Arrays.stream(cell.split(";")).map(String::strip).filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Map<String, Row> readMatrix() throws IOException {
        Map<String, Row> rows = new LinkedHashMap<>();
        try (InputStream in = OwnerPasswordMatrixTest.class.getResourceAsStream("/architecture/owner-password-actions.tsv")) {
            assertThat(in).as("owner-password-actions.tsv on the test classpath").isNotNull();
            List<String> lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(l -> !l.isBlank() && !l.startsWith("#")).toList();
            assertThat(lines.getFirst()).isEqualTo("id\trule\tsource\tphrase\tbuilt");
            for (String line : lines.subList(1, lines.size())) {
                String[] cells = line.split("\t", -1);
                assertThat(cells).as(line).hasSize(5);
                assertThat(cells[1]).as(line).isIn("PROTECTED", "NOT_REQUIRED");
                assertThat(cells[4]).as(line).isIn("yes", "no");
                Row row = new Row(cells[0], cells[1], cells[2], cells[3], "yes".equals(cells[4]));
                assertThat(rows.put(row.id(), row)).as("duplicate id " + row.id()).isNull();
            }
        }
        return rows;
    }
}
