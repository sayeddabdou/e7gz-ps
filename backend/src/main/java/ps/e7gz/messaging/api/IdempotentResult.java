package ps.e7gz.messaging.api;

/**
 * The response of an idempotent write: the status and {@code data} to return, read from the stored representation.
 *
 * @param body     the response {@code data}; {@code null} when the command returned nothing (e.g. a 204)
 * @param replayed {@code true} when the command did not run because the same request had already completed
 */
public record IdempotentResult<R>(int status, R body, boolean replayed) {
}
