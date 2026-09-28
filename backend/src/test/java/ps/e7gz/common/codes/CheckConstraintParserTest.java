package ps.e7gz.common.codes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import ps.e7gz.common.codes.CheckConstraintParser.ArrayUse;
import ps.e7gz.common.codes.CheckConstraintParser.MalformedConstraintException;

/** Inputs are real {@code pg_get_constraintdef} outputs of the migrated database (PostgreSQL 16). */
class CheckConstraintParserTest {

    @Test
    void plainTextList() {
        assertThat(CheckConstraintParser.parse(
                "CHECK ((actor_type = ANY (ARRAY['CUSTOMER'::text, 'VENUE'::text, 'ADMIN'::text, 'SYSTEM'::text])))"))
                .containsExactly(new ArrayUse("actor_type", "= ANY", List.of("CUSTOMER", "VENUE", "ADMIN", "SYSTEM"), false));
    }

    @Test
    void castColumnAndCharacterVaryingElementsWithTextArrayCast() {
        assertThat(CheckConstraintParser.parse(
                "CHECK (((terminal_reason IS NULL) OR ((terminal_reason)::text = ANY ((ARRAY['CUSTOMER_CANCELLED'::character"
                        + " varying, 'VENUE_REJECTED'::character varying])::text[]))))"))
                .containsExactly(new ArrayUse("terminal_reason", "= ANY", List.of("CUSTOMER_CANCELLED", "VENUE_REJECTED"),
                        false));
    }

    @Test
    void twoColumnsInOneConstraint() {
        List<ArrayUse> uses = CheckConstraintParser.parse(
                "CHECK ((((from_console IS NULL) OR ((from_console)::text = ANY ((ARRAY['PS4'::character varying, 'PS5'::"
                        + "character varying])::text[]))) AND ((to_console IS NULL) OR ((to_console)::text = ANY ((ARRAY["
                        + "'PS4'::character varying, 'PS5'::character varying])::text[])))))");

        assertThat(uses).extracting(ArrayUse::column).containsExactly("from_console", "to_console");
        assertThat(uses).allSatisfy(u -> assertThat(u.values()).containsExactly("PS4", "PS5"));
    }

    @Test
    void domainSplitAcrossBranchesAndNotAllAndContainedBy() {
        assertThat(CheckConstraintParser.parse(
                "CHECK (((converted_to IS NULL) OR (((scope)::text = 'VENUE'::text) AND (converted_to = ANY (ARRAY['CLOSURE'"
                        + "::text, 'OPENING_HOURS_CHANGE'::text]))) OR (((scope)::text = 'RESOURCE'::text) AND (converted_to ="
                        + " ANY (ARRAY['MAINTENANCE'::text, 'DISABLED'::text, 'ARCHIVED'::text])))))"))
                .extracting(ArrayUse::column, ArrayUse::values)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("converted_to", List.of("CLOSURE", "OPENING_HOURS_CHANGE")),
                        org.assertj.core.groups.Tuple.tuple("converted_to", List.of("MAINTENANCE", "DISABLED", "ARCHIVED")));

        assertThat(CheckConstraintParser.parse(
                "CHECK ((((status)::text <> ALL ((ARRAY['REJECTED'::character varying])::text[])) AND (x IS NULL)))"))
                .containsExactly(new ArrayUse("status", "<> ALL", List.of("REJECTED"), false));

        assertThat(CheckConstraintParser.parse(
                "CHECK (((recipient_types <@ ARRAY['CUSTOMER'::text, 'VENUE'::text]) AND ((NOT otp_only) OR"
                        + " (allowed_channels = ARRAY['SMS'::text])) AND (('SMS'::text = ANY (allowed_channels)) = otp_only)))"))
                .containsExactly(
                        new ArrayUse("recipient_types", "<@", List.of("CUSTOMER", "VENUE"), false),
                        new ArrayUse("allowed_channels", "=", List.of("SMS"), false));
    }

    @Test
    void numericListsAreFlagged() {
        assertThat(CheckConstraintParser.parse(
                "CHECK (((current_threshold IS NULL) OR (current_threshold = ANY (ARRAY[2, 3]))))"))
                .containsExactly(new ArrayUse("current_threshold", "= ANY", List.of("2", "3"), true));
    }

    @Test
    void duplicatesAreKeptSoTheyCanBeRejected() {
        assertThat(CheckConstraintParser.parse("CHECK ((x = ANY (ARRAY['A'::text, 'A'::text])))").get(0).values())
                .containsExactly("A", "A");
    }

    @Test
    void anUnrecognisedLeftHandSideHasNoColumn() {
        assertThat(CheckConstraintParser.parse("CHECK ((lower(x) = ANY (ARRAY['a'::text])))").get(0).column()).isNull();
    }

    @Test
    void literalEqualityBranches() {
        String idempotencyState = "CHECK ((((state = 'IN_PROGRESS'::text) AND (completed_at IS NULL) AND (response_status"
                + " IS NULL) AND (response_body IS NULL)) OR ((state = 'COMPLETED'::text) AND (completed_at IS NOT NULL) AND"
                + " (completed_at >= created_at) AND (response_status IS NOT NULL) AND ((response_status >= 100) AND"
                + " (response_status <= 599)) AND ((result_aggregate_type IS NULL) = (result_aggregate_id IS NULL)) AND"
                + " ((response_body IS NULL) OR (jsonb_typeof(response_body) = 'object'::text)))))";

        assertThat(CheckConstraintParser.literalComparisons(idempotencyState)).containsExactly(
                new CheckConstraintParser.LiteralUse("state", "=", "IN_PROGRESS"),
                new CheckConstraintParser.LiteralUse("state", "=", "COMPLETED"));
    }

    @Test
    void literalComparisonsSeeCastColumnsAndOtherOperatorsButNotArrayElements() {
        assertThat(CheckConstraintParser.literalComparisons(
                "CHECK (((((status)::text = 'CONVERTED'::text) AND (x IS NOT NULL)) OR (((status)::text <> 'CONVERTED'::text)"
                        + " AND (y = ANY (ARRAY['A'::text, 'B'::text])))))"))
                .containsExactly(
                        new CheckConstraintParser.LiteralUse("status", "=", "CONVERTED"),
                        new CheckConstraintParser.LiteralUse("status", "<>", "CONVERTED"));
    }

    @Test
    void malformedArraysFailLoudly() {
        assertThatThrownBy(() -> CheckConstraintParser.parse("CHECK ((x = ANY (ARRAY['A'::text, y])))"))
                .isInstanceOf(MalformedConstraintException.class);
        assertThatThrownBy(() -> CheckConstraintParser.parse("CHECK ((x = ANY (ARRAY['A'::text"))
                .isInstanceOf(MalformedConstraintException.class);
        assertThatThrownBy(() -> CheckConstraintParser.parse("CHECK ((x = ANY (ARRAY['A"))
                .isInstanceOf(MalformedConstraintException.class);
    }
}
