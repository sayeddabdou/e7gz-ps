package ps.e7gz.common.codes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.common.codes.ClosedValueContract.EQ_ANY;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ps.e7gz.common.codes.ClosedValueContract.Canonical;
import ps.e7gz.common.codes.ClosedValueContract.ContractViolation;
import ps.e7gz.common.codes.ClosedValueContract.Mode;

/** The failure modes of the contract, without a database. */
class ClosedValueContractTest {

    enum Probe { A, B }

    private static final Canonical PROBE =
            new Canonical(Probe.class, "s.t", "ck_probe", "col", EQ_ANY, Mode.SINGLE, 1);

    private static String list(String... values) {
        StringBuilder array = new StringBuilder();
        for (String v : values) {
            array.append(array.isEmpty() ? "" : ", ").append('\'').append(v).append("'::text");
        }
        return "CHECK ((col = ANY (ARRAY[" + array + "])))";
    }

    @Test
    void aMatchingListPasses() {
        List<String> db = ClosedValueContract.extract(PROBE, Map.of("s.t.ck_probe", list("A", "B")));

        assertThat(ClosedValueContract.compare(Probe.class, db).matches()).isTrue();
    }

    @Test
    void aJavaMemberMissingFromTheDatabaseFails() {
        var parity = ClosedValueContract.compare(Probe.class, List.of("A"));

        assertThat(parity.matches()).isFalse();
        assertThat(parity.extraInJava()).containsExactly("B");
    }

    @Test
    void aDatabaseMemberMissingFromJavaFails() {
        var parity = ClosedValueContract.compare(Probe.class, List.of("A", "B", "C"));

        assertThat(parity.matches()).isFalse();
        assertThat(parity.missingInJava()).containsExactly("C");
    }

    @Test
    void aRenamedMemberFailsInBothDirections() {
        var parity = ClosedValueContract.compare(Probe.class, List.of("A", "B2"));

        assertThat(parity.missingInJava()).containsExactly("B2");
        assertThat(parity.extraInJava()).containsExactly("B");
    }

    @Test
    void aNamedConstraintMissingFromTheDatabaseFails() {
        assertThatThrownBy(() -> ClosedValueContract.extract(PROBE, Map.of("s.t.ck_other", list("A", "B"))))
                .isInstanceOf(ContractViolation.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void aListThatParsesToNoMembersFails() {
        assertThatThrownBy(() -> ClosedValueContract.extract(PROBE, "CHECK ((col = ANY (ARRAY[]::text[])))"))
                .isInstanceOf(ContractViolation.class)
                .hasMessageContaining("no members");
        assertThatThrownBy(() -> ClosedValueContract.extract(PROBE, "CHECK ((length(col) > 0))"))
                .as("no list for the column at all")
                .isInstanceOf(ContractViolation.class)
                .hasMessageContaining("found 0");
    }

    @Test
    void duplicatesAreNotHidden() {
        assertThatThrownBy(() -> ClosedValueContract.extract(PROBE, list("A", "B", "A")))
                .isInstanceOf(ContractViolation.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void anUnexpectedNumberOfListsFails() {
        String twoLists = "CHECK (((col = ANY (ARRAY['A'::text])) OR (col = ANY (ARRAY['B'::text]))))";

        assertThatThrownBy(() -> ClosedValueContract.extract(PROBE, twoLists))
                .isInstanceOf(ContractViolation.class)
                .hasMessageContaining("expected 1");
    }

    @Test
    void unionTakesEveryBranchAndRejectsOverlap() {
        Canonical union = new Canonical(Probe.class, "s.t", "ck_probe", "col", EQ_ANY, Mode.UNION, 2);

        assertThat(ClosedValueContract.extract(union,
                "CHECK (((col = ANY (ARRAY['A'::text])) OR (col = ANY (ARRAY['B'::text]))))")).containsExactly("A", "B");
        assertThatThrownBy(() -> ClosedValueContract.extract(union,
                "CHECK (((col = ANY (ARRAY['A'::text])) OR (col = ANY (ARRAY['A'::text, 'B'::text]))))"))
                .isInstanceOf(ContractViolation.class)
                .hasMessageContaining("overlap");
    }

    @Test
    void supersetNeedsOneListContainingTheOthers() {
        Canonical superset = new Canonical(Probe.class, "s.t", "ck_probe", "col", EQ_ANY, Mode.SUPERSET, 2);

        assertThat(ClosedValueContract.extract(superset,
                "CHECK (((col = ANY (ARRAY['A'::text, 'B'::text])) AND (col = ANY (ARRAY['A'::text]))))"))
                .containsExactly("A", "B");
        assertThatThrownBy(() -> ClosedValueContract.extract(superset,
                "CHECK (((col = ANY (ARRAY['A'::text])) AND (col = ANY (ARRAY['B'::text]))))"))
                .isInstanceOf(ContractViolation.class)
                .hasMessageContaining("exactly one list");
    }

    @Test
    void aNumericListIsNotAClosedValueSet() {
        assertThatThrownBy(() -> ClosedValueContract.extract(PROBE, "CHECK ((col = ANY (ARRAY[1, 2])))"))
                .isInstanceOf(ContractViolation.class)
                .hasMessageContaining("numeric");
    }

    // ---- LITERAL_BRANCHES -------------------------------------------------------------------------------------

    private static final Canonical BRANCHES =
            new Canonical(Probe.class, "s.t", "ck_probe", "col", ClosedValueContract.EQ_LITERAL, Mode.LITERAL_BRANCHES, 2);

    @Test
    void literalBranchesAreTheDomain() {
        List<String> db = ClosedValueContract.extract(BRANCHES,
                "CHECK ((((col = 'A'::text) AND (x IS NULL)) OR ((col = 'B'::text) AND (x IS NOT NULL))))");

        assertThat(db).containsExactly("A", "B");
        assertThat(ClosedValueContract.compare(Probe.class, db).matches()).isTrue();
    }

    @Test
    void literalBranchesFailOnMissingConstraintNoBranchDuplicatesOrWrongCount() {
        assertThatThrownBy(() -> ClosedValueContract.extract(BRANCHES, Map.of("s.t.ck_other", "CHECK ((col = 'A'::text))")))
                .isInstanceOf(ContractViolation.class).hasMessageContaining("does not exist");
        assertThatThrownBy(() -> ClosedValueContract.extract(BRANCHES, "CHECK ((x IS NULL))"))
                .isInstanceOf(ContractViolation.class).hasMessageContaining("no members");
        assertThatThrownBy(() -> ClosedValueContract.extract(BRANCHES, "CHECK (((col = 'A'::text) OR (col = 'A'::text)))"))
                .isInstanceOf(ContractViolation.class).hasMessageContaining("duplicate");
        assertThatThrownBy(() -> ClosedValueContract.extract(BRANCHES,
                "CHECK (((col = 'A'::text) OR (col = 'B'::text) OR (col = 'C'::text)))"))
                .isInstanceOf(ContractViolation.class).hasMessageContaining("expected 2");
    }

    @Test
    void literalBranchesMustActuallyCloseTheDomain() {
        assertThatThrownBy(() -> ClosedValueContract.extract(BRANCHES,
                "CHECK (((col = 'A'::text) OR (col <> 'A'::text)))"))
                .isInstanceOf(ContractViolation.class).hasMessageContaining("do not close");
        assertThatThrownBy(() -> ClosedValueContract.extract(BRANCHES,
                "CHECK (((col = 'A'::text) OR (col = ANY (ARRAY['B'::text]))))"))
                .isInstanceOf(ContractViolation.class).hasMessageContaining("ARRAY");
    }

    @Test
    void literalBranchesStillCatchExtraAndMissingJavaMembers() {
        List<String> db = ClosedValueContract.extract(BRANCHES, "CHECK (((col = 'A'::text) OR (col = 'C'::text)))");

        var parity = ClosedValueContract.compare(Probe.class, db);
        assertThat(parity.missingInJava()).containsExactly("C");
        assertThat(parity.extraInJava()).containsExactly("B");
    }

    @Test
    void anUnclassifiedDatabaseListIsReported() {
        assertThat(ClosedValueContract.coverageViolations(Map.of("s.t.ck_new", list("X"))))
                .anyMatch(v -> v.startsWith("Unclassified closed list") && v.contains("s.t.ck_new"));
    }
}
