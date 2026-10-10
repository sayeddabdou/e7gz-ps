package ps.e7gz.messaging.api;

import ps.e7gz.common.error.ApiException;

/**
 * The response of an idempotent write: the status and {@code data} to return, read from the stored representation.
 *
 * @param body     the response {@code data}; {@code null} when the command returned nothing (e.g. a 204) or refused
 * @param replayed {@code true} when the command did not run because the same request had already completed
 * @param refusal  a refusal the command committed ({@link CommandAnswer#refused}), read back from the stored error body;
 *                 {@code null} on success. {@code status} is then its code's status: the caller answers it with
 *                 {@link #throwIfRefused()}, so the first response and every replay go through the same error envelope.
 */
public record IdempotentResult<R>(int status, R body, boolean replayed, ApiException refusal) {

    public IdempotentResult(int status, R body, boolean replayed) {
        this(status, body, replayed, null);
    }

    /** Throws the committed refusal, if any - after the commit, so nothing rolls back - or returns {@code this}. */
    public IdempotentResult<R> throwIfRefused() {
        if (refusal != null) {
            throw refusal;
        }
        return this;
    }
}
