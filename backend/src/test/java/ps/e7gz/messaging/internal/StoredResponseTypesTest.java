package ps.e7gz.messaging.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ps.e7gz.messaging.api.StoredResponse;

/**
 * E7-42: the integration contract of {@code StoredResponse}, checked before any command runs - a response that could
 * carry a secret is refused, and one with a phone field must re-apply the visibility rule on a replay.
 */
class StoredResponseTypesTest {

    private final StoredResponseTypes types = new StoredResponseTypes(new ObjectMapper().registerModule(new Jdk8Module()));

    public record Plain(UUID id, String status, List<String> tags) {
    }

    public record Session(UUID id, String accessToken) {
    }

    public record Inner(String ownerPassword) {
    }

    public record Nested(UUID id, List<Inner> items) {
    }

    public record Wrapped(Optional<Inner> inner) {
    }

    public record Venue(String name, String phone) {
    }

    public record BookingView(UUID bookingId, Venue venue) {
    }

    public record Loose(UUID id, Map<String, Object> details) {
    }

    // ------------------------------------------------------------------- a replay projection may only omit members

    /** A booking-like response: the phone is left out when null, as the contract requires (arch 10 section 5.1). */
    public record Counterpart(String name, @JsonInclude(JsonInclude.Include.NON_NULL) String phone) {
    }

    public record Booking(UUID bookingId, String status, String totalAmount, Counterpart venue,
                          @JsonInclude(JsonInclude.Include.NON_NULL) String phone, List<Counterpart> history) {
    }

    /** The same, but its phone is written as {@code null} instead of being left out. */
    public record NullWritingBooking(UUID bookingId, String phone) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID ID = UUID.fromString("018f3c2e-7b1a-7c4d-9e2f-0a1b2c3d4e5f");
    private static final Booking STORED = new Booking(ID, "CONFIRMED", "250.00",
            new Counterpart("Level Up", "+20223456789"), "+201000000001",
            List.of(new Counterpart("a", "+201000000002"), new Counterpart("b", null)));

    private static Optional<String> check(Object stored, Object shown) {
        return StoredResponseTypes.notOnlyRemoved(JSON.valueToTree(stored), JSON.valueToTree(shown), "/data");
    }

    @Test
    void anUnchangedProjectionIsAllowed() {
        assertThat(check(STORED, STORED)).isEmpty();
    }

    @Test
    void anExistingPhoneMayBeLeftOut() {
        assertThat(check(STORED, new Booking(ID, "CONFIRMED", "250.00", STORED.venue(), null, STORED.history())))
                .isEmpty();
    }

    @Test
    void aNestedPhoneMayBeLeftOutIncludingInsideArrayElements() {
        assertThat(check(STORED, new Booking(ID, "CONFIRMED", "250.00", new Counterpart("Level Up", null), null,
                List.of(new Counterpart("a", null), new Counterpart("b", null))))).isEmpty();
    }

    @Test
    void aNewMemberIsRejectedEvenWhenItIsNull() {
        ObjectNode withNewNull = JSON.valueToTree(STORED);
        withNewNull.putNull("refundReason");
        ObjectNode withNewValue = JSON.valueToTree(STORED);
        withNewValue.put("refundReason", "x");

        assertThat(StoredResponseTypes.notOnlyRemoved(JSON.valueToTree(STORED), withNewNull, "/data"))
                .contains("/data/refundReason");
        assertThat(StoredResponseTypes.notOnlyRemoved(JSON.valueToTree(STORED), withNewValue, "/data"))
                .contains("/data/refundReason");
        // A phone that was absent cannot appear, not even as null.
        Booking noPhone = new Booking(ID, "CONFIRMED", "250.00", STORED.venue(), null, STORED.history());
        ObjectNode phoneBackAsNull = JSON.valueToTree(noPhone);
        phoneBackAsNull.putNull("phone");
        assertThat(StoredResponseTypes.notOnlyRemoved(JSON.valueToTree(noPhone), phoneBackAsNull, "/data"))
                .contains("/data/phone");
    }

    @Test
    void anExistingBookingIdChangedToNullIsRejected() {
        assertThat(check(STORED, new Booking(null, "CONFIRMED", "250.00", STORED.venue(), STORED.phone(),
                STORED.history()))).contains("/data/bookingId");
    }

    @Test
    void anExistingStatusChangedToNullOrAnotherValueIsRejected() {
        assertThat(check(STORED, new Booking(ID, null, "250.00", STORED.venue(), STORED.phone(), STORED.history())))
                .contains("/data/status");
        assertThat(check(STORED, new Booking(ID, "CANCELLED", "250.00", STORED.venue(), STORED.phone(),
                STORED.history()))).contains("/data/status");
        assertThat(check(STORED, new Booking(ID, "CONFIRMED", "0.00", STORED.venue(), STORED.phone(),
                STORED.history()))).contains("/data/totalAmount");
    }

    @Test
    void aNestedObjectReplacedByNullIsRejected() {
        assertThat(check(STORED, new Booking(ID, "CONFIRMED", "250.00", null, STORED.phone(), STORED.history())))
                .contains("/data/venue");
    }

    @Test
    void arrayChangesAreRejected() {
        Counterpart a = STORED.history().get(0);
        Counterpart b = STORED.history().get(1);
        assertThat(check(STORED, withHistory(List.of(a)))).as("removed").contains("/data/history");
        assertThat(check(STORED, withHistory(List.of(a, b, a)))).as("added").contains("/data/history");
        assertThat(check(STORED, withHistory(List.of(b, a)))).as("reordered").contains("/data/history[0]/name");
        assertThat(check(STORED, withHistory(null))).as("nulled").contains("/data/history");
        assertThat(check(STORED, withHistory(List.of(new Counterpart("z", a.phone()), b)))).as("changed")
                .contains("/data/history[0]/name");
    }

    @Test
    void aPhoneWrittenAsNullInsteadOfLeftOutIsRejected() {
        // The contract wants a hidden phone absent, not null: such a DTO must leave a null phone out when written.
        assertThat(check(new NullWritingBooking(ID, "+201000000001"), new NullWritingBooking(ID, null)))
                .contains("/data/phone");
    }

    private static Booking withHistory(List<Counterpart> history) {
        return new Booking(ID, "CONFIRMED", "250.00", STORED.venue(), STORED.phone(), history);
    }

    // ------------------------------------------------------------------------------- the response type contract

    @Test
    void aSecretFreeResponseIsAccepted() {
        assertThatCode(() -> types.require(StoredResponse.of(Plain.class, 201))).doesNotThrowAnyException();
    }

    @Test
    void aResponseWithASecretFieldIsRefusedNamingItsPath() {
        assertThatThrownBy(() -> types.require(StoredResponse.of(Session.class, 201)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("/accessToken");
        assertThatThrownBy(() -> types.require(StoredResponse.of(Nested.class, 201)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("/items[]/ownerPassword");
        assertThatThrownBy(() -> types.require(StoredResponse.of(Wrapped.class, 201)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("/inner[]/ownerPassword");
    }

    @Test
    void aResponseWithAPhoneNeedsAReplayProjection() {
        assertThatThrownBy(() -> types.require(StoredResponse.of(BookingView.class, 201)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("/venue/phone")
                .hasMessageContaining("ReplayProjection");
        assertThatCode(() -> types.require(StoredResponse.of(BookingView.class, 201)
                .replayedThrough((context, stored) -> stored))).doesNotThrowAnyException();
    }

    @Test
    void aSecretInAnUntypedFieldIsFoundOnTheActualResponse() {
        ObjectMapper json = new ObjectMapper();
        assertThatCode(() -> types.require(StoredResponse.of(Loose.class, 201))).doesNotThrowAnyException();

        Loose withSecret = new Loose(UUID.randomUUID(), Map.of("list", List.of(Map.of("pin", "4821"))));
        assertThat(StoredResponseTypes.secretPath(json.valueToTree(withSecret), "/data"))
                .contains("/data/details/list[0]/pin");
        assertThat(StoredResponseTypes.secretPath(json.valueToTree(new Loose(UUID.randomUUID(), Map.of("a", 1))), ""))
                .isEmpty();
    }
}
