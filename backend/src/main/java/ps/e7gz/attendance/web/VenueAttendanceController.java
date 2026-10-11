package ps.e7gz.attendance.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.codes.AttendanceResult;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.NotImplementedYet;
import ps.e7gz.common.web.OpenApiConfig;

/**
 * Venue staff record attendance (arch 09 §4.1, arch 07 §7.1, FRS §4.6). Stubs of the Sprint 3 contract (E7-54) until
 * E7-59. None needs the Owner Password (FR-ATT-01, FR-ATT-03, FR-ATT-04); each is in ADR-015's scope, so the
 * Idempotency-Key is required. Every action is audited with the venue account, the instant and the Booking ID.
 * The windows are half-open and given to the Dashboard as instants on the booking ({@code VenueBooking.attendance}).
 * A manual booking has no attendance actions (FR-MAN-04): each answers its own 422 code.
 */
@RestController
@RequestMapping("/api/v1/venue/bookings/{bookingId}")
@Tag(name = "venue-attendance")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class VenueAttendanceController {

    @PostMapping("/check-in")
    @Operation(operationId = "venueCheckIn",
            summary = "Check the customer in while startAt <= now < startAt + 15 min (FR-ATT-01, FR-ATT-02)",
            description = "No QR code, PIN or customer device (FR-ATT-01). Outside the window — including exactly "
                    + "startAt + 15 min — it is CHECK_IN_WINDOW_CLOSED, and Mark Present — Late becomes the action "
                    + "(FR-ATT-02). A manual booking is CHECK_IN_WINDOW_CLOSED too (FR-MAN-04). A booking no longer in "
                    + "progress (cancelled, completed, no-show) is BOOKING_STATE_CHANGED. A booking of another venue is "
                    + "NOT_FOUND. Implemented by E7-59.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.BOOKING_STATE_CHANGED)
    @ApiError(status = 422, codes = {ApiErrorCode.CHECK_IN_WINDOW_CLOSED, ApiErrorCode.IDEMPOTENCY_KEY_REUSED})
    public DataResponse<VenueAttendance> checkIn(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @PathVariable UUID bookingId) {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/late-present")
    @Operation(operationId = "venueMarkLatePresent",
            summary = "Mark Present — Late while startAt + 15 min <= now < endAt (FR-ATT-03)",
            description = "Only with no valid check-in and no committed no-show; otherwise, or outside the window, it "
                    + "is LATE_PRESENT_NOT_AVAILABLE. It blocks a later no-show (FR-ATT-04) and by itself grants no "
                    + "points, no Verified Player progress and no counter reset. A manual booking is "
                    + "LATE_PRESENT_NOT_AVAILABLE too (FR-MAN-04). A booking no longer in progress is "
                    + "BOOKING_STATE_CHANGED. A booking of another venue is NOT_FOUND. Implemented by E7-59.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.BOOKING_STATE_CHANGED)
    @ApiError(status = 422, codes = {ApiErrorCode.LATE_PRESENT_NOT_AVAILABLE, ApiErrorCode.IDEMPOTENCY_KEY_REUSED})
    public DataResponse<VenueAttendance> markLatePresent(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @PathVariable UUID bookingId) {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/no-show")
    @Operation(operationId = "venueMarkNoShow",
            summary = "Record a no-show while startAt + 15 min <= now < endAt: NO_SHOW / ABSENT_NO_SHOW (FR-ATT-04, "
                    + "FR-ATT-05)",
            description = "Only with no valid check-in and no Mark Present — Late; otherwise, or outside the window, "
                    + "it is NO_SHOW_NOT_AVAILABLE. Terminal: the remaining interval is released at once (FR-ATT-05) "
                    + "and venue staff cannot undo it — only a Platform Admin can (FR-ATT-06, FR-ATT-11). A manual "
                    + "booking is NO_SHOW_NOT_AVAILABLE too (FR-MAN-04). A booking no longer in progress is "
                    + "BOOKING_STATE_CHANGED. A booking of another venue is NOT_FOUND. Implemented by E7-59.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.BOOKING_STATE_CHANGED)
    @ApiError(status = 422, codes = {ApiErrorCode.NO_SHOW_NOT_AVAILABLE, ApiErrorCode.IDEMPOTENCY_KEY_REUSED})
    public DataResponse<VenueAttendance> markNoShow(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @PathVariable UUID bookingId) {
        throw NotImplementedYet.stub();
    }

    /** The booking's attendance after the action: the evidence recorded, each an instant (FR-ATT-07). */
    @Schema(name = "VenueAttendance")
    public record VenueAttendance(
            @NotNull @Schema(example = "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e6f") UUID bookingId,
            @NotNull @Schema(description = "NO_SHOW after a no-show, otherwise unchanged (ACTIVE).") BookingStatus status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "Independent of status (FR-ATT-07): ABSENT_NO_SHOW after a no-show, otherwise null "
                            + "until the booking completes.") AttendanceResult attendanceResult,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "2026-10-12T18:04:00Z")
            Instant checkedInAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "2026-10-12T18:20:00Z")
            Instant latePresentAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "2026-10-12T18:20:00Z")
            Instant noShowAt) {
    }
}
