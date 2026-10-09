package ps.e7gz.messaging.api;

import java.util.Objects;
import java.util.function.Function;

/**
 * What an idempotent write stores when its command succeeds, and how a replay reads it back.
 *
 * <p>Stored: the HTTP status, and the response {@code data} exactly as the API returns it, as {@code {"data": …}} in
 * {@code response_body} - never the per-request {@code meta}, which a replay builds afresh. Both the first response and
 * every replay are read back from the stored representation, so they are equal - except for what a
 * {@link ReplayProjection} hides on a replay.
 *
 * <p>Integration contract, checked by {@link Idempotency#wrap} before the command runs (a wiring defect, so an
 * {@code IllegalArgumentException}):
 * <ul>
 *   <li>The response type has no field with a secret's name (password, token, OTP, PIN, ...: the database's
 *       {@code technical.forbidden_payload_key}). Such a response cannot be stored, so the write cannot be idempotent
 *       this way; return a projection without the secret instead. The static check sees the declared properties of
 *       records and beans, through lists, arrays, map values and {@code Optional}. It cannot see member names that
 *       exist only at run time: {@code Map} keys, {@code Object} / {@code JsonNode} fields, a subtype's extra fields
 *       behind an interface or abstract type, {@code @JsonAnyGetter} and custom serializers. So the actual response is
 *       checked again before {@code idempotency_complete}: a secret-named member anywhere in it throws an
 *       {@code IllegalStateException} naming its path (never its value), and the whole command rolls back - the
 *       business change and the idempotency record with it.</li>
 *   <li>A response type with a phone field ({@code phone}, {@code customerPhone}, ...) declares a
 *       {@link ReplayProjection} with {@link #replayedThrough}, which re-applies the counterpart phone visibility rule
 *       (arch 10 section 5.1) on a replay. Declaring one is necessary, not sufficient: see {@link ReplayProjection}
 *       for what the owning module must implement and test.</li>
 * </ul>
 *
 * @param type       the response {@code data} type; a replay reads the stored body back into it
 * @param status     the HTTP status of a success (e.g. 201 for a create)
 * @param aggregate  the aggregate the write created or changed, or {@code null} for none
 * @param onReplay   applied to the stored response on a replay only, or {@code null} for none
 */
public record StoredResponse<R>(Class<R> type, int status, Function<? super R, ResultAggregate> aggregate,
                                ReplayProjection<R> onReplay) {

    public StoredResponse {
        Objects.requireNonNull(type, "type");
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("HTTP status out of range: " + status);   // ck_idempotency_state
        }
    }

    public static <R> StoredResponse<R> of(Class<R> type, int status) {
        return new StoredResponse<>(type, status, null, null);
    }

    public static <R> StoredResponse<R> of(Class<R> type, int status, Function<? super R, ResultAggregate> aggregate) {
        return new StoredResponse<>(type, status, Objects.requireNonNull(aggregate, "aggregate"), null);
    }

    /** The same, with {@code projection} applied to the stored response on every replay. */
    public StoredResponse<R> replayedThrough(ReplayProjection<R> projection) {
        return new StoredResponse<>(type, status, aggregate, Objects.requireNonNull(projection, "projection"));
    }
}
