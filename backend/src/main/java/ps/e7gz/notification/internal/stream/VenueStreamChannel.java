package ps.e7gz.notification.internal.stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The PostgreSQL {@code NOTIFY} channel and message between the worker and every API replica (arch 08 section 5): one
 * channel {@code venue_{venueAccountId}} per venue account, so a venue never hears another venue's signals. A message
 * is a change signal ({@code {"kind":"change","type","id","reason"}}) or {@code {"kind":"close"}} when the account
 * was disabled. Signals only, never business data: far under NOTIFY's 8 kB, and nothing in it is trusted.
 */
public final class VenueStreamChannel {

    static final String PREFIX = "venue_";

    /** What changed; the Dashboard refetches it. */
    public enum Type {
        BOOKING,
        TOURNAMENT
    }

    /** CREATED (a new booking request) and REQUESTED (a new tournament request) play the audible alert (A6). */
    public enum Reason {
        CREATED,
        REQUESTED,
        CHANGED
    }

    /** One change signal: exactly the {@code data} of an SSE {@code change} event. */
    public record Signal(Type type, UUID id, Reason reason) {
    }

    /** A message read from the channel. */
    sealed interface Message {
        record Change(Signal signal) implements Message {
        }

        record Close() implements Message {
        }
    }

    private VenueStreamChannel() {
    }

    static String channel(UUID venueAccountId) {
        return PREFIX + venueAccountId;
    }

    static Optional<UUID> account(String channel) {
        if (channel == null || !channel.startsWith(PREFIX)) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(channel.substring(PREFIX.length())));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    static String change(Signal signal, ObjectMapper json) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("kind", "change");
        message.put("type", signal.type().name());
        message.put("id", signal.id().toString());
        message.put("reason", signal.reason().name());
        return write(message, json);
    }

    static String close(ObjectMapper json) {
        return write(Map.of("kind", "close"), json);
    }

    /** Empty for anything that is not a well-formed message (it is ignored, never trusted). */
    static Optional<Message> read(String payload, ObjectMapper json) {
        try {
            JsonNode node = json.readTree(payload);
            return switch (node.path("kind").asText("")) {
                case "close" -> Optional.of(new Message.Close());
                case "change" -> Optional.of(new Message.Change(new Signal(
                        Type.valueOf(node.path("type").asText()),
                        UUID.fromString(node.path("id").asText()),
                        Reason.valueOf(node.path("reason").asText()))));
                default -> Optional.empty();
            };
        } catch (Exception malformed) {
            return Optional.empty();
        }
    }

    private static String write(Map<String, Object> message, ObjectMapper json) {
        try {
            return json.writeValueAsString(message);
        } catch (Exception e) {
            throw new IllegalStateException("Unwritable venue stream message", e);
        }
    }
}
