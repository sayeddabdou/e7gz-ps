package ps.e7gz.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

/**
 * E7-31 - the Venue schedule route takes an optional {@code date}: without one the server uses the Venue's current
 * business date and returns the date it used as {@code VenueSchedule.businessDate} (arch 04 section 4.3). Read from the
 * committed contract, which {@link OpenApiContractTest} keeps equal to the controllers.
 */
class VenueAvailabilityContractTest {

    @Test
    void theScheduleDateIsOptionalAndTheResponseCarriesTheBusinessDateUsed() throws Exception {
        JsonNode contract = OpenApiDocument.parse(Files.readString(OpenApiDocument.COMMITTED));
        JsonNode operation = contract.path("paths").path("/api/v1/venue/availability").path("get");

        JsonNode date = StreamSupport.stream(operation.path("parameters").spliterator(), false)
                .filter(p -> "query".equals(p.path("in").asText()) && "date".equals(p.path("name").asText()))
                .findFirst().orElseThrow();
        assertThat(date.path("required").asBoolean(false)).as("date is optional").isFalse();
        assertThat(date.path("schema").path("format").asText()).isEqualTo("date");

        JsonNode schedule = contract.path("components").path("schemas").path("VenueSchedule");
        assertThat(schedule.path("required")).anyMatch(field -> "businessDate".equals(field.asText()));
    }
}
