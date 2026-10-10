package ps.e7gz.booking;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.support.fixtures.Fixtures.aDefaultPrice;
import static ps.e7gz.support.fixtures.Fixtures.aResource;
import static ps.e7gz.support.fixtures.Fixtures.aUser;
import static ps.e7gz.support.fixtures.Fixtures.aVenue;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.fixtures.Fixtures;

/**
 * E7-46 - {@code GET /api/v1/customer/bookings/quote} over HTTP (PO decisions 5 and 9 Oct 2026): the amounts as Money
 * strings with the fee shown free at EGP 0, 404 for an unknown resource and the 422 codes. Creation over HTTP is
 * {@code CustomerBookingCreateHttpTest}.
 */
@AutoConfigureMockMvc
class CustomerBookingQuoteHttpTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AccessTokens accessTokens;

    private String token;
    private Fixtures.Resource room;

    @BeforeEach
    void customerAndPricedRoom() {
        token = accessTokens.issue(AuthSubjectType.CUSTOMER, aUser().verified().create().id());
        Fixtures.Venue venue = aVenue().active().create();
        room = aResource().at(venue).playStation(ConsoleType.PS5).create();
        aDefaultPrice().forVenue(venue).playStation(ConsoleType.PS5, PlayMode.SINGLE).hourly("77.77").create();
    }

    private ResultActions quote(UUID resourceId, String playMode, int minutes) throws Exception {
        var request = get("/api/v1/customer/bookings/quote").header("Authorization", "Bearer " + token)
                .param("resourceId", resourceId.toString()).param("startAt", "2030-06-01T18:00:00Z")
                .param("durationMinutes", Integer.toString(minutes));
        if (playMode != null) {
            request.param("playMode", playMode);
        }
        return mvc.perform(request);
    }

    @Test
    void theQuoteAnswersTheAmountsAsMoneyAndAZeroFeeAsFree() throws Exception {
        quote(room.id(), "SINGLE", 90)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hourlyPrice.amount").value("77.77"))
                .andExpect(jsonPath("$.data.hourlyPrice.currency").value("EGP"))
                .andExpect(jsonPath("$.data.nominalBookedMinutes").value(90))
                .andExpect(jsonPath("$.data.venuePriceTotal.amount").value("116.66"))
                .andExpect(jsonPath("$.data.bookingFee.amount").value("0.00"))
                .andExpect(jsonPath("$.data.bookingFee.currency").value("EGP"))
                .andExpect(jsonPath("$.data.bookingFee.displayAsFree").value(true))
                .andExpect(jsonPath("$.data.totalAmount.amount").value("116.66"));
    }

    @Test
    void anUnknownResourceIsNotFound() throws Exception {
        quote(UUID.randomUUID(), "SINGLE", 90)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void anInvalidDurationAndAMissingPriceAreTheir422Codes() throws Exception {
        quote(room.id(), "SINGLE", 45)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("BOOKING_DURATION_INVALID"));
        quote(room.id(), "MULTIPLAYER", 90)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_ELIGIBLE"));
        quote(room.id(), null, 90)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_ELIGIBLE"));
    }
}
