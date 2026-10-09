package ps.e7gz.messaging.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.ActorType;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.security.AuthenticatedSubject;
import ps.e7gz.messaging.api.Idempotency;
import ps.e7gz.messaging.api.IdempotentRequest;
import ps.e7gz.messaging.api.IdempotentResult;
import ps.e7gz.messaging.api.ResultAggregate;
import ps.e7gz.messaging.api.StoredResponse;

/**
 * {@link Idempotency} over {@code technical.idempotency_begin} / {@code idempotency_complete} (ADR-015, arch 08
 * section 6), inside the business command's own transaction: no transaction, lock or cache of its own.
 */
@Component
class IdempotencyService implements Idempotency {

    /** arch 08 section 6: "Records are retained 30 days", from the command instant. */
    static final Duration RETENTION = Duration.ofDays(30);

    private static final Pattern ROUTE = Pattern.compile("^[A-Z]+ /api/v1/(customer|venue|admin)/\\S*$");
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^}/]+)}");

    private final ObjectMapper json;
    private final JdbcIdempotencyStore store;
    private final RequestFingerprint fingerprint;
    private final StoredResponseTypes responseTypes;

    IdempotencyService(ObjectMapper json, JdbcIdempotencyStore store, RequestFingerprint fingerprint) {
        this.json = json;
        this.store = store;
        this.fingerprint = fingerprint;
        this.responseTypes = new StoredResponseTypes(json);
    }

    @Override
    public IdempotentRequest request(AuthenticatedSubject principal, String endpoint, UUID key,
                                     Map<String, ?> pathVariables, Object body) {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(pathVariables, "pathVariables");
        requireOwnRouteGroup(principal, endpoint);
        requireTemplateVariables(endpoint, pathVariables);

        ObjectNode canonicalInput = json.createObjectNode();
        canonicalInput.set("path", json.valueToTree(new TreeMap<>(pathVariables)));
        canonicalInput.set("body", body == null ? NullNode.getInstance() : json.valueToTree(body));
        return new IdempotentRequest(ActorType.valueOf(principal.type().name()), principal.id(), endpoint,
                key.toString(), fingerprint.of(canonicalInput));
    }

    @Override
    public <R> Command<IdempotentResult<R>> wrap(IdempotentRequest request, StoredResponse<R> response,
                                                 Command<R> business) {
        responseTypes.require(Objects.requireNonNull(response, "response"));
        return new IdempotentCommand<>(Objects.requireNonNull(request, "request"),
                Objects.requireNonNull(response, "response"), Objects.requireNonNull(business, "business"));
    }

    /** A customer writes under /customer, a venue account under /venue, an admin under /admin (arch 09 section 1.1). */
    private static void requireOwnRouteGroup(AuthenticatedSubject principal, String endpoint) {
        Matcher route = ROUTE.matcher(endpoint);
        if (!route.matches() || !route.group(1).equalsIgnoreCase(principal.type().name())) {
            String group = principal.type().name().toLowerCase(Locale.ROOT);
            throw new IllegalArgumentException("endpoint must be 'METHOD /api/v1/" + group + "/...': " + endpoint);
        }
    }

    /** Every variable of the path template, and nothing else, is part of the fingerprint. */
    private static void requireTemplateVariables(String endpoint, Map<String, ?> pathVariables) {
        Set<String> names = new LinkedHashSet<>();
        Matcher variable = PATH_VARIABLE.matcher(endpoint);
        while (variable.find()) {
            names.add(variable.group(1));
        }
        if (!names.equals(pathVariables.keySet())) {
            throw new IllegalArgumentException("path variables " + pathVariables.keySet()
                    + " must be exactly the template's " + names + ": " + endpoint);
        }
        pathVariables.forEach((name, value) -> Objects.requireNonNull(value, "path variable " + name));
    }

    /**
     * The response as stored: {@code {"data": …}} as JSON text, or {@code null} for a command that returned nothing.
     * A secret-named member (one a {@code Map} or {@code Object} field slipped past {@link StoredResponseTypes}) is a
     * defect: refused here, before the database would refuse it, so the message names the path. The command rolls back.
     */
    private String stored(Object data, IdempotentRequest request) {
        if (data == null) {
            return null;
        }
        JsonNode tree = json.createObjectNode().set("data", json.valueToTree(data));
        StoredResponseTypes.secretPath(tree, "").ifPresent(path -> {
            throw new IllegalStateException("The response of " + request.endpoint() + " has a secret field at " + path
                    + " and cannot be stored (technical.forbidden_payload_key)");
        });
        try {
            return json.writeValueAsString(tree);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Response of an idempotent write cannot be stored", e);
        }
    }

    private <R> R read(String stored, Class<R> type) {
        if (stored == null) {
            return null;
        }
        try {
            JsonNode data = json.readTree(stored).get("data");
            return data == null ? null : json.treeToValue(data, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored response of an idempotent write cannot be read as " + type, e);
        }
    }

    /**
     * begin → the business command (only when new) → complete, in the transaction {@code CommandRunner} opened for
     * {@code business}'s own category and lock set; on {@code 40001} the runner reruns all of it.
     */
    private final class IdempotentCommand<R> implements Command<IdempotentResult<R>> {

        private final IdempotentRequest request;
        private final StoredResponse<R> response;
        private final Command<R> business;

        IdempotentCommand(IdempotentRequest request, StoredResponse<R> response, Command<R> business) {
            this.request = request;
            this.response = response;
            this.business = business;
        }

        @Override
        public EligibilityCategory eligibilityCategory() {
            return business.eligibilityCategory();
        }

        @Override
        public CommandScope scope() {
            return business.scope();
        }

        @Override
        public IdempotentResult<R> execute(CommandContext context) {
            JdbcIdempotencyStore.Begun begun = store.begin(context.jdbc(), request,
                    context.commandNow().plus(RETENTION), CorrelationId.require());
            if (begun instanceof JdbcIdempotencyStore.Begun.Replay replay) {
                return new IdempotentResult<>(replay.status(), replayed(context, replay.body()), true);
            }
            R result = business.execute(context);
            String body = stored(result, request);
            ResultAggregate aggregate = result == null || response.aggregate() == null
                    ? null : response.aggregate().apply(result);
            store.complete(context.jdbc(), request, response.status(), body, aggregate);
            return new IdempotentResult<>(response.status(), read(body, response.type()), false);
        }

        /**
         * The stored response through the owning module's {@code ReplayProjection}, if any. The Platform checks the half
         * of the projection's contract it can see - that it only left data out ({@link StoredResponseTypes#notOnlyRemoved})
         * - and refuses anything else as a defect. That it leaves out what its rule hides now is the owning module's
         * responsibility and test.
         */
        private R replayed(CommandContext context, String body) {
            R stored = read(body, response.type());
            if (stored == null || response.onReplay() == null) {
                return stored;
            }
            R shown = response.onReplay().onReplay(context, stored);
            if (shown == null) {
                throw new IllegalStateException("The ReplayProjection of " + request.endpoint() + " returned null");
            }
            StoredResponseTypes.notOnlyRemoved(json.valueToTree(stored), json.valueToTree(shown), "/data")
                    .ifPresent(path -> {
                        throw new IllegalStateException("The ReplayProjection of " + request.endpoint()
                                + " may only leave data out, but changed " + path);
                    });
            return shown;
        }
    }
}
