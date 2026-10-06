package ps.e7gz.notification.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import ps.e7gz.common.web.NotImplementedYet;
import ps.e7gz.common.web.OpenApiConfig;

/**
 * The venue live channel (ADR-014, arch 08 §5): one SSE stream per venue account carrying change signals only, never
 * business payloads; the Dashboard refetches on a signal and keeps a 30-second poll as a backstop. Stub until E7-44.
 *
 * <p>Authentication is the ordinary Bearer header (PO decision 5 Oct 2026, E7-39): the Dashboard reads the stream
 * with {@code fetch()} and a {@code ReadableStream}, because a browser {@code EventSource} cannot send headers. No
 * token ever travels in the URL.
 */
@RestController
@RequestMapping("/api/v1/venue/stream")
@Tag(name = "venue-stream")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class VenueStreamController {

    static final String STREAM_EXAMPLE = """
            event: change
            data: {"type":"BOOKING","id":"018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e6f","reason":"CREATED"}

            : heartbeat

            """;

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(operationId = "venueStream",
            summary = "Server-sent change signals for this venue account (ADR-014, arch 08 §5)",
            description = "Each event is `event: change` with a VenueStreamSignal as `data`; a `: heartbeat` comment "
                    + "every 15 s. The stream closes when the venue account becomes DISABLED (FR-VEN-08). Delivery is "
                    + "at most once: on reconnect, refetch what is on screen. Read it with fetch() and the Bearer "
                    + "header, not EventSource. Implemented by E7-44.",
            responses = @ApiResponse(responseCode = "200", description = "An open event stream.",
                    content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                            schema = @Schema(implementation = VenueStreamSignal.class),
                            // A literal event, so `pnpm mock` can serve the stream as text (Prism cannot write an
                            // object as text/event-stream).
                            examples = @ExampleObject(name = "bookingCreated", value = STREAM_EXAMPLE))))
    public SseEmitter stream() {
        throw NotImplementedYet.stub();
    }

    /** The {@code data} of one {@code change} event: what to refetch, never the thing itself. */
    @Schema(name = "VenueStreamSignal")
    public record VenueStreamSignal(
            @NotNull VenueStreamSignalType type,
            @NotNull @Schema(example = "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e6f") UUID id,
            @NotNull VenueStreamSignalReason reason) {
    }

    @Schema(name = "VenueStreamSignalType", enumAsRef = true)
    public enum VenueStreamSignalType {
        BOOKING,
        TOURNAMENT
    }

    /**
     * Why the entity changed (arch 08 §5). CREATED (a new booking request) and REQUESTED (a new tournament request)
     * play the audible alert (01 §14 A6); CHANGED is any other change.
     */
    @Schema(name = "VenueStreamSignalReason", enumAsRef = true)
    public enum VenueStreamSignalReason {
        CREATED,
        REQUESTED,
        CHANGED
    }
}
