package ps.e7gz.messaging.internal;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import ps.e7gz.messaging.api.StoredResponse;

/**
 * The integration contract of {@link StoredResponse} (see there), checked on the response type as Jackson writes it -
 * once per type, before any command runs - and, as the last line before {@code technical.idempotency_complete}, on the
 * actual response tree. Messages name the type and the JSON path, never a value.
 */
final class StoredResponseTypes {

    private final ObjectMapper json;
    private final Map<Class<?>, Fields> checked = new ConcurrentHashMap<>();

    StoredResponseTypes(ObjectMapper json) {
        this.json = json;
    }

    /** JSON paths of the type's secret-named and phone fields. */
    record Fields(List<String> secrets, List<String> phones) {
    }

    void require(StoredResponse<?> response) {
        Fields fields = checked.computeIfAbsent(response.type(), this::fields);
        if (!fields.secrets().isEmpty()) {
            throw new IllegalArgumentException(response.type().getName() + " has secret fields " + fields.secrets()
                    + ": an idempotent write cannot store it (technical.forbidden_payload_key); return a projection "
                    + "without them");
        }
        if (!fields.phones().isEmpty() && response.onReplay() == null) {
            throw new IllegalArgumentException(response.type().getName() + " has phone fields " + fields.phones()
                    + ": declare a ReplayProjection that re-applies the phone visibility rule (arch 10 section 5.1)");
        }
    }

    /** The JSON path of the first secret-named member of a stored response, if any. */
    static Optional<String> secretPath(JsonNode node, String path) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> member : node.properties()) {
                String at = path + "/" + member.getKey();
                if (RequestFingerprint.isSecretName(member.getKey())) {
                    return Optional.of(at);
                }
                Optional<String> inside = secretPath(member.getValue(), at);
                if (inside.isPresent()) {
                    return inside;
                }
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                Optional<String> inside = secretPath(node.get(i), path + "[" + i + "]");
                if (inside.isPresent()) {
                    return inside;
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The first JSON path where {@code shown} (a replay after its {@code ReplayProjection}, as the API's mapper writes
     * it) is not the stored response with object members left out. Strict on purpose:
     * <ul>
     *   <li>an object may only <b>omit</b> members: a member the stored object does not have - even one whose value is
     *       {@code null} - is an addition;</li>
     *   <li>a scalar must be <b>equal</b>: a value changed to {@code null} is a change (so is a type change);</li>
     *   <li>an array keeps its size and order; each element is compared by these same rules.</li>
     * </ul>
     * There is no {@code null} exception, not even for a phone: the contract says a hidden phone is <i>absent</i>, never
     * {@code null} (arch 10 section 5.1), so the owning DTO leaves a {@code null} phone out when written
     * ({@code @JsonInclude(NON_NULL)} on it) and the projection's {@code null} phone arrives here as an omission.
     * Empty when {@code shown} only omitted members.
     */
    static Optional<String> notOnlyRemoved(JsonNode stored, JsonNode shown, String path) {
        if (stored.isObject()) {
            if (!shown.isObject()) {
                return Optional.of(path);
            }
            for (Map.Entry<String, JsonNode> member : shown.properties()) {
                String at = path + "/" + member.getKey();
                JsonNode original = stored.get(member.getKey());
                if (original == null) {
                    return Optional.of(at);
                }
                Optional<String> inside = notOnlyRemoved(original, member.getValue(), at);
                if (inside.isPresent()) {
                    return inside;
                }
            }
            return Optional.empty();
        }
        if (stored.isArray()) {
            if (!shown.isArray() || shown.size() != stored.size()) {
                return Optional.of(path);
            }
            for (int i = 0; i < shown.size(); i++) {
                Optional<String> inside = notOnlyRemoved(stored.get(i), shown.get(i), path + "[" + i + "]");
                if (inside.isPresent()) {
                    return inside;
                }
            }
            return Optional.empty();
        }
        return shown.equals(stored) ? Optional.empty() : Optional.of(path);
    }

    static boolean isPhoneName(String name) {
        return name.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT).contains("phone");
    }

    private Fields fields(Class<?> type) {
        List<String> secrets = new ArrayList<>();
        List<String> phones = new ArrayList<>();
        walk(json.constructType(type), "", secrets, phones, new HashSet<>());
        return new Fields(List.copyOf(secrets), List.copyOf(phones));
    }

    private void walk(JavaType type, String path, List<String> secrets, List<String> phones, Set<JavaType> seen) {
        if (type.isContainerType() || type.isReferenceType()) {     // arrays, collections, map values, Optional
            walk(type.getContentType(), path + "[]", secrets, phones, seen);
            return;
        }
        Class<?> raw = type.getRawClass();
        if (type.isPrimitive() || type.isEnumType() || raw.getName().startsWith("java.") || !seen.add(type)) {
            return;
        }
        BeanDescription bean = json.getSerializationConfig().introspect(type);
        for (BeanPropertyDefinition property : bean.findProperties()) {
            if (!property.couldSerialize()) {
                continue;
            }
            String at = path + "/" + property.getName();
            if (RequestFingerprint.isSecretName(property.getName())) {
                secrets.add(at);
            } else if (isPhoneName(property.getName())) {
                phones.add(at);
            }
            walk(property.getPrimaryType(), at, secrets, phones, seen);
        }
    }
}
