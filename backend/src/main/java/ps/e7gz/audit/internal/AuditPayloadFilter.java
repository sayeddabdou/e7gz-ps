package ps.e7gz.audit.internal;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import ps.e7gz.audit.api.AuditPayloadAllowlist;

/**
 * Builds a new payload that contains only the allowlisted leaf paths of the candidate (arch 04 section 9: payload is
 * "redaction-allowlisted"). It never copies the candidate and removes things: it starts empty and adds what is allowed.
 *
 * <ul>
 *   <li>A leaf is kept only when its exact path is allowlisted and its value is a plain scalar (string, boolean,
 *       integer, {@link BigDecimal}, {@link UUID}, {@link Instant}, {@link LocalDate}, enum). Objects of any other type
 *       are dropped, never serialized reflectively; {@code float}/{@code double} are dropped (no floating point).</li>
 *   <li>A nested object is entered only when some allowlisted path lies below it, and then filtered the same way, so
 *       an unknown field inside an allowed structure never passes.</li>
 *   <li>A candidate key must be one plain field name ({@code [A-Za-z][A-Za-z0-9_]*}); a key containing path syntax,
 *       such as {@code "window.startAt"} or {@code "items[].id"}, is dropped, so paths match only real structure.</li>
 *   <li>Nulls, non-string keys and anything empty after filtering are dropped.</li>
 * </ul>
 */
final class AuditPayloadFilter {

    /** A candidate object key: one plain field name, never path syntax ({@code .}, {@code []}). */
    private static final Pattern PLAIN_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    private AuditPayloadFilter() {
    }

    static Map<String, Object> filter(AuditPayloadAllowlist allowlist, Map<String, ?> candidate) {
        return filterObject(allowlist.paths(), candidate, "");
    }

    private static Map<String, Object> filterObject(Set<String> allowed, Map<?, ?> object, String prefix) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : object.entrySet()) {
            // Path syntax belongs to the allowlist only: a key like "window.startAt" or "items[].id" must not
            // impersonate a nested field, so a candidate key has to be one plain segment.
            if (!(entry.getKey() instanceof String key) || !PLAIN_KEY.matcher(key).matches()
                    || entry.getValue() == null) {
                continue;
            }
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Object value = entry.getValue();
            Object kept = null;
            if (isScalar(value)) {
                kept = allowed.contains(path) ? scalar(value) : null;
            } else if (value instanceof Map<?, ?> nested) {
                kept = hasPathBelow(allowed, path + ".") ? nonEmpty(filterObject(allowed, nested, path)) : null;
            } else if (value instanceof Collection<?> list) {
                kept = filterList(allowed, list, path + "[]");
            }
            if (kept != null) {
                out.put(key, kept);
            }
        }
        return out;
    }

    private static List<Object> filterList(Set<String> allowed, Collection<?> list, String path) {
        List<Object> out = new ArrayList<>();
        if (allowed.contains(path)) {
            list.stream().filter(AuditPayloadFilter::isScalar).map(AuditPayloadFilter::scalar).forEach(out::add);
        } else if (hasPathBelow(allowed, path + ".")) {
            for (Object element : list) {
                if (element instanceof Map<?, ?> object) {
                    Map<String, Object> filtered = filterObject(allowed, object, path);
                    if (!filtered.isEmpty()) {
                        out.add(filtered);
                    }
                }
            }
        }
        return out.isEmpty() ? null : out;
    }

    private static boolean hasPathBelow(Set<String> allowed, String prefix) {
        return allowed.stream().anyMatch(p -> p.startsWith(prefix));
    }

    private static Map<String, Object> nonEmpty(Map<String, Object> map) {
        return map.isEmpty() ? null : map;
    }

    private static boolean isScalar(Object value) {
        return value instanceof String || value instanceof Boolean
                || value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte
                || value instanceof BigInteger || value instanceof BigDecimal
                || value instanceof UUID || value instanceof Instant || value instanceof LocalDate
                || value instanceof Enum<?>;
    }

    private static Object scalar(Object value) {
        if (value instanceof Enum<?> e) {
            return e.name();
        }
        if (value instanceof UUID || value instanceof Instant || value instanceof LocalDate) {
            return value.toString();
        }
        return value;
    }
}
