package ps.e7gz.venue.internal;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * Object storage for venue photo bytes (arch 12 §2); {@code venue.venue_image} holds the metadata. Every call is a
 * network call: never make one inside a database transaction (arch 01 §15).
 */
interface ObjectStore {

    /** A signed PUT that accepts exactly this type and size; the client sends the same Content-Type. */
    URI signedPut(String key, String contentType, long sizeBytes, Duration ttl);

    URI signedGet(String key, Duration ttl);

    Optional<StoredObject> head(String key);

    /** The object's bytes, or empty when there is none. Callers keep objects small (photos are at most a few MB). */
    Optional<byte[]> read(String key);

    void write(String key, byte[] bytes, String contentType);

    void delete(String key);

    record StoredObject(long sizeBytes, String contentType) {
    }
}
