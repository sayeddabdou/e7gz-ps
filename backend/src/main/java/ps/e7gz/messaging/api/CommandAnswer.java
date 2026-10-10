package ps.e7gz.messaging.api;

import java.util.Objects;
import ps.e7gz.common.error.ApiException;

/**
 * What an idempotent business command answers when a refusal must still commit (E7-47, PO decision D2 10 Oct 2026): a
 * command whose writes are themselves the outcome of the refusal - e.g. FR-BOK-16, where a venue's Accept stopped by an
 * emergency cancels the booking under that cause and the venue gets a 422. Throwing the {@link ApiException} would roll
 * the cancellation back; returning {@link #refused} commits every write of the command together with an Idempotency-Key
 * record that stores the refusal's own status and error body, so the first response and every replay are the same 4xx.
 *
 * <p>Give such a command to {@link Idempotency#wrapAnswer}; a command that never commits a refusal keeps using
 * {@link Idempotency#wrap}, and a refusal that should change nothing is still thrown, as before.
 */
public sealed interface CommandAnswer<R> {

    /** The command succeeded with {@code value}: stored and returned under the {@link StoredResponse}'s status. */
    static <R> CommandAnswer<R> ok(R value) {
        return new Ok<>(value);
    }

    /** The command's writes commit, and the request answers {@code refusal}: its code's status and its error body. */
    static <R> CommandAnswer<R> refused(ApiException refusal) {
        return new Refused<>(refusal);
    }

    record Ok<R>(R value) implements CommandAnswer<R> {
    }

    record Refused<R>(ApiException refusal) implements CommandAnswer<R> {

        public Refused {
            Objects.requireNonNull(refusal, "refusal");
        }
    }
}
