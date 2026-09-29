package ps.e7gz.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import ps.e7gz.support.RepoFiles;

/**
 * The identifier table of {@link DatabaseErrorCodes} against the migration that sets the identifiers: every
 * identifier the database can raise is classified (mapped, or deliberately unmapped), nothing is mapped that the
 * database never raises, and matching is exact on SQLSTATE and identifier. Real raises: {@code GuardErrorIdentifiersTest}.
 */
class DatabaseErrorCodesTest {

    private static final String MIGRATION = "backend/src/main/resources/db/migration/V202609291200__guard_error_identifiers.sql";

    @Test
    void everyIdentifierTheMigrationRaisesIsClassifiedAndNothingElseIsMapped() throws IOException {
        Set<String> raised = identifiers(Files.readString(RepoFiles.get(MIGRATION), StandardCharsets.UTF_8));

        Set<String> classified = new HashSet<>(DatabaseErrorCodes.GUARDS.keySet());
        classified.addAll(DatabaseErrorCodes.UNMAPPED_GUARDS);
        assertThat(new TreeSet<>(raised)).isEqualTo(new TreeSet<>(classified));
        assertThat(DatabaseErrorCodes.GUARDS.keySet()).doesNotContainAnyElementsOf(DatabaseErrorCodes.UNMAPPED_GUARDS);
    }

    @Test
    void aGuardIdentifierMapsOnlyUnderTheRaiseSqlState() {
        assertThat(DatabaseErrorCodes.codeFor("P0001", "gd_username_cooldown")).contains(ApiErrorCode.USERNAME_COOLDOWN_ACTIVE);
        assertThat(DatabaseErrorCodes.codeFor("23505", "gd_username_cooldown")).isEmpty();
        assertThat(DatabaseErrorCodes.codeFor("40001", "gd_username_cooldown")).isEmpty();
    }

    @Test
    void aConstraintMapsOnlyUnderItsOwnSqlState() {
        assertThat(DatabaseErrorCodes.codeFor("23P01", "ex_no_double_booking")).contains(ApiErrorCode.SLOT_NO_LONGER_AVAILABLE);
        assertThat(DatabaseErrorCodes.codeFor("23505", "ex_no_double_booking")).isEmpty();
        assertThat(DatabaseErrorCodes.codeFor("P0001", "ex_no_double_booking")).isEmpty();
        // Not every constraint violation is a slot conflict.
        assertThat(DatabaseErrorCodes.codeFor("23P01", "ex_session_no_overlap")).isEmpty();
        assertThat(DatabaseErrorCodes.codeFor("23505", "idempotency_record_pk")).isEmpty();
    }

    @Test
    void noIdentifierOrUnmappedIdentifierIsUnexpected() {
        assertThat(DatabaseErrorCodes.codeFor("P0001", null)).isEmpty();
        assertThat(DatabaseErrorCodes.codeFor("P0001", "gd_online_booking_area_inactive")).isEmpty();
        assertThat(DatabaseErrorCodes.codeFor("P0001", "gd_something_new")).isEmpty();
        assertThat(DatabaseErrorCodes.codeFor(null, "gd_username_cooldown")).isEmpty();
    }

    @Test
    void aFailureWithoutPostgresDiagnosticsIsUnexpected() {
        assertThat(DatabaseErrorCodes.translate(new DataIntegrityViolationException("x", new SQLException("x", "23P01"))))
                .isEmpty();
        assertThat(DatabaseErrorCodes.translate(new IllegalStateException("x"))).isEmpty();
    }

    private static Set<String> identifiers(String sql) {
        Set<String> found = new HashSet<>();
        Matcher m = Pattern.compile("'(gd_[a-z_]+)'").matcher(sql);
        while (m.find()) {
            found.add(m.group(1));
        }
        assertThat(found).as("identifiers parsed from the migration").isNotEmpty();
        return found;
    }
}
