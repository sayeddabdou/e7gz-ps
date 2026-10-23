package ps.e7gz.ports;

import java.util.UUID;
import ps.e7gz.common.money.Money;

/**
 * DEFERRED (ADR-018, arch 03 section 18): the payment provider port for a future non-zero Booking Fee. It has no
 * implementation and no caller at launch: payment collection and settlement are out of scope (FR-PTS-13, PRD section
 * 3.4). Enabling a fee is then a configuration change plus one adapter, with no schema or logic redesign (NFR-38).
 *
 * <p>Nothing under {@code ps.e7gz.booking}, {@code ps.e7gz.loyalty} or {@code ps.e7gz.tournament} may import it
 * ({@code PaymentProviderBoundaryTest}): the booking path never depends on a payment integration.
 */
public interface PaymentProvider {

    AuthorizationResult authorize(PaymentIntent intent);

    CaptureResult capture(AuthorizationId id);

    RefundResult refund(CaptureId id, Money amount);

    PaymentStatus status(PaymentReference reference);

    /** What to authorise: the Booking Fee of one booking. */
    record PaymentIntent(UUID bookingId, Money amount) {
    }

    record AuthorizationId(String value) {
    }

    record CaptureId(String value) {
    }

    record PaymentReference(String value) {
    }

    record AuthorizationResult(boolean authorized, AuthorizationId id) {
    }

    record CaptureResult(boolean captured, CaptureId id) {
    }

    record RefundResult(boolean refunded) {
    }

    enum PaymentStatus {
        PENDING, AUTHORIZED, CAPTURED, REFUNDED, FAILED
    }
}
