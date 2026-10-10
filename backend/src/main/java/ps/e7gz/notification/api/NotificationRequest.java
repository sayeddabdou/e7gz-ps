package ps.e7gz.notification.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import ps.e7gz.common.codes.NotificationRecipientType;

/**
 * One notification to create (Spec v5 section 52, arch 08 section 4.4): a {@code notification_catalogue} code, the one
 * recipient, the Booking or Tournament it is about, and machine values for the surface's text - never prose (ADR-021)
 * and never a secret (the database refuses a secret-named member: {@code technical.forbidden_payload_key}).
 *
 * <p>Use the factory that matches the code's {@code reference_type}: a booking code must name its Booking (FR-NTF-04,
 * arch 08 section 4.5), a tournament code its Tournament, any other code neither. The database checks it, and that the
 * recipient is a party of the Booking or Tournament ({@code notification.assert_recipient}).
 *
 * @param recipientId a User id (CUSTOMER), a Venue id (VENUE) or an Admin id (ADMIN)
 */
public record NotificationRequest(String code, NotificationRecipientType recipientType, UUID recipientId,
                                  UUID bookingId, UUID tournamentId, Map<String, Object> params) {

    public NotificationRequest {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(recipientType, "recipientType");
        Objects.requireNonNull(recipientId, "recipientId");
        if (bookingId != null && tournamentId != null) {
            throw new IllegalArgumentException("a notification references a Booking or a Tournament, not both");
        }
        params = params == null ? Map.of() : new LinkedHashMap<>(params);
    }

    /** A code whose {@code reference_type} is {@code NONE}. */
    public static NotificationRequest of(String code, NotificationRecipientType recipientType, UUID recipientId,
                                         Map<String, Object> params) {
        return new NotificationRequest(code, recipientType, recipientId, null, null, params);
    }

    /** A code whose {@code reference_type} is {@code BOOKING}. */
    public static NotificationRequest aboutBooking(String code, NotificationRecipientType recipientType,
                                                   UUID recipientId, UUID bookingId, Map<String, Object> params) {
        return new NotificationRequest(code, recipientType, recipientId, Objects.requireNonNull(bookingId, "bookingId"),
                null, params);
    }

    /** A code whose {@code reference_type} is {@code TOURNAMENT}. */
    public static NotificationRequest aboutTournament(String code, NotificationRecipientType recipientType,
                                                      UUID recipientId, UUID tournamentId, Map<String, Object> params) {
        return new NotificationRequest(code, recipientType, recipientId, null,
                Objects.requireNonNull(tournamentId, "tournamentId"), params);
    }
}
