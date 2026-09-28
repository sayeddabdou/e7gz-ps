package ps.e7gz.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import ps.e7gz.support.IntegrationTest;

/**
 * The committed {@code openapi.json} must match the controllers, so CI fails when an endpoint changed without
 * {@code pnpm api:gen} (E7-11). With {@code -De7gz.openapi.write=true} (Gradle task {@code generateOpenApi}) it
 * writes the file instead of comparing.
 */
@AutoConfigureMockMvc(addFilters = false)
class OpenApiContractTest extends IntegrationTest {

    private static final boolean WRITE = Boolean.getBoolean("e7gz.openapi.write");

    @Autowired
    MockMvc mvc;

    @Test
    void committedContractMatchesTheControllers() throws Exception {
        String generated = OpenApiDocument.fetch(mvc);
        if (WRITE) {
            Files.writeString(OpenApiDocument.COMMITTED, generated);
            return;
        }
        assertThat(OpenApiDocument.COMMITTED).as("packages/api-client/openapi.json — run `pnpm api:gen`").exists();
        // Compared as JSON, so a CRLF checkout on Windows does not count as a change.
        assertThat(OpenApiDocument.parse(Files.readString(OpenApiDocument.COMMITTED)))
                .as("packages/api-client/openapi.json is out of date — run `pnpm api:gen` and commit the result")
                .isEqualTo(OpenApiDocument.parse(generated));
    }

    @Test
    void sampleControllersStayOutOfTheProductionContract() throws Exception {
        assertThat(OpenApiDocument.parse(OpenApiDocument.fetch(mvc)).path("paths")
                .has(OpenApiSampleContractTest.SAMPLE_PATH)).isFalse();
    }
}
