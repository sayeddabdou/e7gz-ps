package ps.e7gz.messaging.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import ps.e7gz.messaging.api.OutboxConsumer;

/**
 * Every {@link OutboxConsumer} of the worker, by event type. The dispatcher knows nothing else about a type: a module
 * plugs in by adding a consumer bean. A type no consumer takes is never polled; its events wait, untouched.
 */
final class ConsumerRegistry {

    /** {@code ck_outbox_event_names}. */
    private static final Pattern TYPE_FORMAT = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,127}");

    private final Map<String, List<OutboxConsumer>> byType = new LinkedHashMap<>();

    ConsumerRegistry(Collection<? extends OutboxConsumer> consumers) {
        for (OutboxConsumer consumer : consumers) {
            for (String type : consumer.eventTypes()) {
                if (type == null || !TYPE_FORMAT.matcher(type).matches()) {
                    throw new IllegalStateException("Event type '" + type + "' of " + consumer.getClass().getName()
                            + " does not match ck_outbox_event_names");
                }
                byType.computeIfAbsent(type, t -> new ArrayList<>()).add(consumer);
            }
        }
    }

    /** The consumers of the type, in a fixed order; empty when none takes it. */
    List<OutboxConsumer> consumers(String type) {
        return byType.getOrDefault(type, List.of());
    }

    Set<String> types() {
        return byType.keySet();
    }
}
