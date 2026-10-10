package ps.e7gz.messaging.api;

import java.util.Map;
import java.util.UUID;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.security.AuthenticatedSubject;

/**
 * Idempotent writes (ADR-015, arch 08 section 6): a write in ADR-015's scope runs once per
 * (principal, endpoint, {@code Idempotency-Key}); a retry with the same request returns the stored response without
 * running the command again, and a retry with a different request is {@code 422 IDEMPOTENCY_KEY_REUSED}.
 *
 * <p>Use it from the controller of a write whose operation declares
 * {@code @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey} (the header is then required):
 * <pre>{@code
 * IdempotentRequest request = idempotency.request(subject, "POST /api/v1/customer/bookings", idempotencyKey,
 *         Map.of(), body);
 * IdempotentResult<CustomerBookingDetail> result = runner.run(idempotency.wrap(request,
 *         StoredResponse.of(CustomerBookingDetail.class, 201, d -> new ResultAggregate("BOOKING", d.bookingId())),
 *         new CreateBookingCommand(...)));
 * return ResponseEntity.status(result.status()).body(meta.data(result.body()));
 * }</pre>
 *
 * <p>The wrapped command runs in the business command's own {@code CommandRunner} transaction, after
 * {@code begin_command} has taken its locks: {@code technical.idempotency_begin}, then the command (only when the
 * request is new), then {@code technical.idempotency_complete}, then one commit. A command that throws rolls everything
 * back, the idempotency record included, so the client may retry with the same key (arch 08 section 6).
 */
public interface Idempotency {

    /**
     * The identity and fingerprint of one write request.
     *
     * @param principal     the authenticated caller; its type and id are the record's principal (the type keeps a
     *                      customer, a venue account and an admin with the same id apart)
     * @param endpoint      the operation as {@code METHOD /api/v1/<route group>/<path template>}, e.g.
     *                      {@code POST /api/v1/venue/bookings/{bookingId}/accept}; the route group must be the
     *                      principal's own
     * @param key           the {@code Idempotency-Key} header
     * @param pathVariables exactly the variables of the path template, by name (empty when it has none)
     * @param body          the bound request body, or {@code null} for an operation without one. A member whose name is
     *                      a secret's (Owner Password, PIN, token, OTP, ...) enters the fingerprint as an HMAC under the
     *                      server's fingerprint key, never in plain text: a changed secret is a different request
     */
    IdempotentRequest request(AuthenticatedSubject principal, String endpoint, UUID key, Map<String, ?> pathVariables,
                              Object body);

    /**
     * The command to give {@code CommandRunner.run}: the same eligibility category and lock set as {@code business},
     * with the idempotency record begun and completed around it in its transaction. On a replay {@code business} does
     * not run and the stored status and body are returned (through {@code response}'s {@link ReplayProjection}, if any).
     *
     * @throws IllegalArgumentException when {@code response} breaks the integration contract of {@link StoredResponse}
     *                                  (a secret field, or a phone field without a replay projection)
     */
    <R> Command<IdempotentResult<R>> wrap(IdempotentRequest request, StoredResponse<R> response, Command<R> business);

    /**
     * {@link #wrap} for a command that may commit a refusal (E7-47, PO decision D2 10 Oct 2026). An
     * {@link CommandAnswer#ok} is stored and returned exactly as {@link #wrap} does. A {@link CommandAnswer#refused}
     * commits the command's writes and stores the refusal's code status with its error body
     * ({@code {"error": {code, field?, details?}}}, the envelope without {@code meta}); the result carries it as
     * {@link IdempotentResult#refusal()}, and a replay reads the same status and refusal back without running anything.
     * The caller answers it with {@link IdempotentResult#throwIfRefused()}. An exception thrown by {@code business}
     * still rolls everything back, the idempotency record included.
     */
    <R> Command<IdempotentResult<R>> wrapAnswer(IdempotentRequest request, StoredResponse<R> response,
                                                Command<CommandAnswer<R>> business);
}
