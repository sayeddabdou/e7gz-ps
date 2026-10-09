package ps.e7gz.messaging.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ps.e7gz.messaging.api.IdempotentRequest;
import ps.e7gz.messaging.api.ResultAggregate;

/**
 * SQL over {@code technical.idempotency_record}, only through its producer functions: the record's guard trigger
 * refuses any other write (ADR-026). Every method runs on the {@link JdbcClient} of the caller's command, so the record
 * commits or rolls back with the business change.
 */
@Component
class JdbcIdempotencyStore {

    /** What {@code technical.idempotency_begin} found. */
    sealed interface Begun {

        /** No live record: one is now {@code IN_PROGRESS} in this transaction; run the command. */
        record New() implements Begun {
        }

        /** The same request completed before: its stored status and body ({@code null} when it stored none). */
        record Replay(int status, String body) implements Begun {
        }
    }

    /**
     * {@code technical.idempotency_begin}. A live record with another fingerprint raises {@code P0001}
     * {@code gd_idempotency_key_reused} (422 {@code IDEMPOTENCY_KEY_REUSED} through {@code DatabaseErrorCodes}). A record
     * another transaction is writing makes this one wait on the primary key until that one ends: after a commit it is a
     * replay, after a rollback this request is new.
     */
    Begun begin(JdbcClient jdbc, IdempotentRequest request, Instant expiresAt, UUID correlationId) {
        return jdbc.sql("""
                        SELECT outcome, response_status, response_body::text AS response_body
                          FROM technical.idempotency_begin(:principalType, :principalId, :endpoint, :key, :fingerprint,
                                                           :expiresAt, :correlation)""")
                .param("principalType", request.principalType().name())
                .param("principalId", request.principalId())
                .param("endpoint", request.endpoint())
                .param("key", request.key())
                .param("fingerprint", request.fingerprint())
                .param("expiresAt", OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC))
                .param("correlation", correlationId)
                .query((rs, row) -> switch (rs.getString("outcome")) {
                    case "NEW" -> (Begun) new Begun.New();
                    case "REPLAY" -> new Begun.Replay(rs.getInt("response_status"), rs.getString("response_body"));
                    default -> throw new IllegalStateException("Unknown idempotency outcome " + rs.getString("outcome"));
                })
                .single();
    }

    /**
     * {@code technical.idempotency_complete}: {@code IN_PROGRESS -> COMPLETED} with the response. The database refuses a
     * body with a secret field ({@code technical.forbidden_payload_key}).
     */
    void complete(JdbcClient jdbc, IdempotentRequest request, int status, String body, ResultAggregate aggregate) {
        jdbc.sql("""
                        SELECT technical.idempotency_complete(:principalType, :principalId, :endpoint, :key,
                                                              CAST(:status AS smallint), CAST(:body AS jsonb),
                                                              CAST(:aggregateType AS text), CAST(:aggregateId AS uuid))""")
                .param("principalType", request.principalType().name())
                .param("principalId", request.principalId())
                .param("endpoint", request.endpoint())
                .param("key", request.key())
                .param("status", status)
                .param("body", body)
                .param("aggregateType", aggregate == null ? null : aggregate.type())
                .param("aggregateId", aggregate == null ? null : aggregate.id())
                .query().singleRow();
    }
}
