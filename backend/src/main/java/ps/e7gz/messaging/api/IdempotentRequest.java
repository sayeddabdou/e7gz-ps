package ps.e7gz.messaging.api;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import ps.e7gz.common.codes.ActorType;

/**
 * One write request as {@code technical.idempotency_record} identifies it: the primary key
 * (principal type, principal id, endpoint, key) and the fingerprint of the canonical request. Built by
 * {@link Idempotency#request}.
 *
 * @param fingerprint lower-case hex SHA-256 of the canonical request ({@code ck_idempotency_values})
 */
public record IdempotentRequest(ActorType principalType, UUID principalId, String endpoint, String key,
                                String fingerprint) {

    private static final Pattern ENDPOINT = Pattern.compile("^(POST|PUT|PATCH|DELETE) /\\S+$");
    private static final Pattern FINGERPRINT = Pattern.compile("^[0-9a-f]{64}$");

    public IdempotentRequest {
        Objects.requireNonNull(principalType, "principalType");
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(fingerprint, "fingerprint");
        // The limits of ck_idempotency_values, so a wiring defect fails here rather than as an unmapped CHECK.
        if (endpoint.length() > 200 || !ENDPOINT.matcher(endpoint).matches()) {
            throw new IllegalArgumentException("endpoint must be 'METHOD /path' of at most 200 characters: " + endpoint);
        }
        if (key.isEmpty() || key.length() > 255) {
            throw new IllegalArgumentException("idempotency key must have 1 to 255 characters");
        }
        if (!FINGERPRINT.matcher(fingerprint).matches()) {
            throw new IllegalArgumentException("fingerprint must be lower-case hex SHA-256");
        }
    }
}
