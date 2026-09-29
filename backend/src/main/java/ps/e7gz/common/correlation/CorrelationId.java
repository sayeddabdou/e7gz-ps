package ps.e7gz.common.correlation;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * The correlation ID of the unit of work running on this thread (NFR-32, arch 11 §5.1: "correlationId is the spine").
 * It is a UUID because every {@code correlation_id} column in the model is {@code uuid NOT NULL} (arch 04).
 *
 * <p>Where it comes from:
 * <ul>
 *   <li><b>HTTP request</b> — {@code CorrelationIdFilter} accepts the {@code X-Correlation-Id} header (a UUID) or
 *       generates one, opens a {@link Scope} for the request, echoes the ID on the response and closes the scope in
 *       {@code finally}, so nothing leaks to the next request served by the same thread.</li>
 *   <li><b>Background work</b> (worker profile) — a worker never reads a request's thread state: that request has
 *       finished. It opens a scope from the ID persisted on the row it processes:
 *       {@code try (var scope = CorrelationId.open(job.correlationId())) { … }}.</li>
 * </ul>
 *
 * <p>How audit and outbox get it (E7-17 audit writer, outbox task): the command layer reads {@link #require()} once,
 * at the edge of the command, and passes the UUID <b>explicitly</b> — as {@code CommandScope.Admin} already does for
 * {@code admin.begin_command(admin, correlation, …)} and as the producer functions take {@code p_correlation}. The
 * audit writer stores it in {@code audit.audit_event.correlation_id}; the outbox writer stores it in
 * {@code technical.outbox_event.correlation_id} in the same transaction; the dispatcher and every scheduled job reopen
 * it with {@link #open(UUID)} from that column. Those writers belong to their own tasks and are not part of E7-12.
 *
 * <p>The ID is mirrored into the SLF4J MDC under {@link #MDC_KEY} so every log line of the unit of work carries it.
 */
public final class CorrelationId {

    /** The request/response header (arch 09 §1.2). */
    public static final String HEADER = "X-Correlation-Id";

    /** The MDC key the log pattern prints. */
    public static final String MDC_KEY = "correlationId";

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private CorrelationId() {
    }

    /** The current ID, if a scope is open on this thread. */
    public static Optional<UUID> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** The current ID; fails loudly when called outside a request or a worker scope (a wiring defect). */
    public static UUID require() {
        UUID id = CURRENT.get();
        if (id == null) {
            throw new IllegalStateException("No correlation ID on this thread: open CorrelationId.open(...) first");
        }
        return id;
    }

    /** A new random ID for a unit of work that starts without one. */
    public static UUID generate() {
        return UUID.randomUUID();
    }

    /**
     * Opens a scope with this ID. Scopes nest: closing restores the previous ID (and MDC value), so a worker can open
     * one per job on a pooled thread.
     */
    public static Scope open(UUID id) {
        Objects.requireNonNull(id, "id");
        UUID previous = CURRENT.get();
        CURRENT.set(id);
        MDC.put(MDC_KEY, id.toString());
        return new Scope(previous);
    }

    /** Parses a client-supplied header value; empty unless it is a UUID. */
    public static Optional<UUID> parse(String header) {
        if (header == null || header.isBlank() || header.length() > 36) {
            return Optional.empty();
        }
        try {
            UUID id = UUID.fromString(header.strip());
            // UUID.fromString accepts short forms such as "1-1-1-1-1"; only the canonical 36-character form is taken.
            return id.toString().equalsIgnoreCase(header.strip()) ? Optional.of(id) : Optional.empty();
        } catch (IllegalArgumentException notAUuid) {
            return Optional.empty();
        }
    }

    /** Restores the previous ID when closed. Close it in {@code finally} / try-with-resources. */
    public static final class Scope implements AutoCloseable {

        private final UUID previous;
        private boolean closed;

        private Scope(UUID previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                CURRENT.remove();
                MDC.remove(MDC_KEY);
            } else {
                CURRENT.set(previous);
                MDC.put(MDC_KEY, previous.toString());
            }
        }
    }
}
