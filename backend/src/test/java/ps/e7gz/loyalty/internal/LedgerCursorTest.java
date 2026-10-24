package ps.e7gz.loyalty.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;

/** E7-66 - the opaque cursors of the points and wallet pages round-trip, and anything else is VALIDATION_FAILED. */
class LedgerCursorTest {

    @Test
    void aTransactionCursorRoundTrips() {
        UUID id = UUID.randomUUID();
        assertThat(LedgerCursor.transaction(LedgerCursor.encode(id))).isEqualTo(id);
    }

    @Test
    void anAccountPositionRoundTripsWithItsExactBalance() {
        LedgerCursor.Position position = new LedgerCursor.Position(new BigDecimal("12.50"), UUID.randomUUID());
        assertThat(LedgerCursor.position(LedgerCursor.encode(position))).isEqualTo(position);
    }

    @Test
    void noCursorIsTheFirstPage() {
        assertThat(LedgerCursor.transaction(null)).isNull();
        assertThat(LedgerCursor.transaction(" ")).isNull();
        assertThat(LedgerCursor.position(null)).isNull();
        assertThat(LedgerCursor.size(null)).isEqualTo(LedgerCursor.DEFAULT_PAGE_SIZE);
        assertThat(LedgerCursor.size(7)).isEqualTo(7);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-cursor", "AAAA", "%%%"})
    void aMalformedTransactionCursorIsAValidationFailureOnCursor(String cursor) {
        assertThatThrownBy(() -> LedgerCursor.transaction(cursor)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.code()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
            assertThat(e.field()).isEqualTo("cursor");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"no-separator", "abc|" + "00000000-0000-0000-0000-000000000000", "1|not-a-uuid"})
    void aMalformedPositionIsAValidationFailureOnCursor(String plain) {
        String cursor = Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> LedgerCursor.position(cursor)).isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.code()).isEqualTo(ApiErrorCode.VALIDATION_FAILED));
    }
}
