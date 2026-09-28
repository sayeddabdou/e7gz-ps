package ps.e7gz.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.support.IntegrationTest;

/**
 * The backend half of the backend → OpenAPI → TypeScript chain (E7-11). Sample controllers and DTOs go through the
 * real springdoc setup, and the resulting test-only contract is written to {@code build/generated-contracts/}.
 * packages/api-client generates types from it, type-checks a client against it and mocks it ({@code test:chain}).
 *
 * <p>The sample controller is nested in this test class, so Spring's test type filter keeps it out of every other
 * context and out of the committed {@code openapi.json} ({@link OpenApiContractTest} checks that).
 */
@AutoConfigureMockMvc(addFilters = false)
@Import(OpenApiSampleContractTest.SampleController.class)
class OpenApiSampleContractTest extends IntegrationTest {

    static final String SAMPLE_PATH = "/api/v1/customer/samples/{sampleId}";
    static final String SAMPLE_NOTES_PATH = "/api/v1/customer/samples/{sampleId}/notes";
    static final String SAMPLE_COMMITMENTS_PATH = "/api/v1/customer/samples/{sampleId}/commitments";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void writesTheSampleContractForTheTypeScriptChain() throws Exception {
        String document = OpenApiDocument.fetch(mvc);
        Path out = Path.of(System.getProperty("e7gz.sampleContract", "build/generated-contracts/sample.openapi.json"));
        Files.createDirectories(out.getParent());
        Files.writeString(out, document);

        JsonNode json = OpenApiDocument.parse(document);
        assertThat(json.path("openapi").asText()).startsWith("3.1");
        assertThat(json.path("servers").get(0).path("url").asText()).isEqualTo("/");

        JsonNode get = json.path("paths").path(SAMPLE_PATH).path("get");
        assertThat(get.path("parameters").get(0).path("in").asText()).isEqualTo("path");
        assertThat(get.path("parameters").get(0).path("required").asBoolean()).isTrue();
        assertThat(get.path("responses").has("200")).isTrue();

        JsonNode post = json.path("paths").path(SAMPLE_NOTES_PATH).path("post");
        assertThat(post.path("requestBody").path("required").asBoolean()).isTrue();
        assertThat(post.path("responses").has("201")).isTrue();
        assertThat(post.path("responses").has("422")).isTrue();

        JsonNode schemas = json.path("components").path("schemas");
        assertThat(texts(schemas.path("SampleView").path("required"))).contains("sampleId", "status")
                .doesNotContain("note");
        assertThat(texts(schemas.path("SampleNoteRequest").path("required"))).contains("text", "status");
        assertThat(schemas.path("SampleView").path("properties").path("status").path("$ref").asText())
                .isEqualTo("#/components/schemas/SampleStatus");
    }

    @Test
    void aDeclaredRequiredIdempotencyKeyStaysRequiredAndAppearsOnce() throws Exception {
        JsonNode paths = OpenApiDocument.parse(OpenApiDocument.fetch(mvc)).path("paths");

        List<JsonNode> required = idempotencyHeaders(paths.path(SAMPLE_COMMITMENTS_PATH).path("post"));
        assertThat(required).hasSize(1);
        assertThat(required.get(0).path("required").asBoolean()).isTrue();

        // An ordinary write keeps the shared, optional header.
        List<JsonNode> optional = idempotencyHeaders(paths.path(SAMPLE_NOTES_PATH).path("post"));
        assertThat(optional).hasSize(1);
        assertThat(optional.get(0).path("required").asBoolean()).isFalse();
    }

    private static List<JsonNode> idempotencyHeaders(JsonNode operation) {
        List<JsonNode> headers = new ArrayList<>();
        operation.path("parameters").forEach(parameter -> {
            if ("header".equals(parameter.path("in").asText())
                    && OpenApiConfig.IDEMPOTENCY_KEY.equalsIgnoreCase(parameter.path("name").asText())) {
                headers.add(parameter);
            }
        });
        return headers;
    }

    @Test
    void enumValuesAreExactlyHowJacksonSerialisesTheJavaEnum() throws Exception {
        JsonNode schemas = OpenApiDocument.parse(OpenApiDocument.fetch(mvc)).path("components").path("schemas");

        List<String> serialised = new ArrayList<>();
        for (SampleStatus status : SampleStatus.values()) {
            serialised.add(objectMapper.readTree(objectMapper.writeValueAsString(status)).asText());
        }

        assertThat(serialised).containsExactly("OPEN", "LATE_CONFIRMED", "CLOSED");
        assertThat(texts(schemas.path("SampleStatus").path("enum"))).containsExactlyElementsOf(serialised);
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    @Schema(enumAsRef = true)
    enum SampleStatus {
        OPEN,
        LATE_CONFIRMED,
        CLOSED
    }

    record SampleView(
            @NotNull @Schema(example = "s-1") String sampleId,
            @NotNull @Schema(example = "OPEN") SampleStatus status,
            @Schema(example = "window seat") String note) {
    }

    record SampleNoteRequest(
            @NotBlank @Schema(example = "bring two controllers") String text,
            @NotNull SampleStatus status) {
    }

    record SampleErrorBody(@NotNull @Schema(example = "SAMPLE_CLOSED") String code) {
    }

    record SampleError(@NotNull SampleErrorBody error) {
    }

    @RestController
    @RequestMapping("/api/v1/customer/samples")
    static class SampleController {

        @GetMapping("/{sampleId}")
        SampleView get(@PathVariable String sampleId) {
            return new SampleView(sampleId, SampleStatus.OPEN, null);
        }

        @PostMapping("/{sampleId}/notes")
        @ResponseStatus(HttpStatus.CREATED)
        @ApiResponse(responseCode = "201", description = "Created")
        @ApiResponse(responseCode = "422", description = "Refused",
                content = @Content(schema = @Schema(implementation = SampleError.class)))
        SampleView addNote(@PathVariable String sampleId, @Valid @RequestBody SampleNoteRequest request) {
            return new SampleView(sampleId, request.status(), request.text());
        }

        /** A write in ADR-015's scope: it declares the Idempotency-Key itself, as required. */
        @PostMapping("/{sampleId}/commitments")
        @ResponseStatus(HttpStatus.CREATED)
        SampleView commit(@PathVariable String sampleId,
                @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
                @Valid @RequestBody SampleNoteRequest request) {
            return new SampleView(sampleId, request.status(), request.text());
        }
    }
}
