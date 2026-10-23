package ps.e7gz.attendance.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.codes.AttendanceResult;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.NotImplementedYet;
import ps.e7gz.common.web.OpenApiConfig;

/**
 * Platform Admin attendance remediation (arch 09 §5, arch 07 §7.4, FR-ATT-11, FR-ATT-12, FR-ATT-13). In the Sprint 4
 * contract by PO decision D1 (17 Oct 2026); stubs until E7-70. Only a Platform Admin reverses attendance (FR-ATT-11).
 * Each action needs a reason, is audited (FR-AUD-06), notifies the customer (FR-NTF-11) and is in ADR-015's scope.
 * Corrections are append-only trust events; released inventory is never resurrected (FR-TRS-24). Evidence that does
 * not exist, a manual booking (no attendance, FR-MAN-04) or a booking whose state does not allow the action is
 * ADMIN_ACTION_NOT_ALLOWED (PO decision D5).
 */
@RestController
@RequestMapping("/api/v1/admin/bookings/{bookingId}/attendance")
@Tag(name = "admin-attendance")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class AdminAttendanceController {

    @PostMapping("/reverse-check-in")
    @Operation(operationId = "adminReverseCheckIn", summary = "Reverse a check-in (FR-ATT-12)",
            description = "While ACTIVE: the booking stays ACTIVE and no-show eligibility re-opens within its window. "
                    + "After an ATTENDED completion: an ATTENDANCE_CORRECTED event; the points are reversed by a "
                    + "compensating transaction (arch 07 §7.4). Implemented by E7-70.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED)
    @ApiError(status = 422, codes = ApiErrorCode.IDEMPOTENCY_KEY_REUSED)
    public DataResponse<AdminAttendance> reverseCheckIn(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @PathVariable UUID bookingId,
            @Valid @RequestBody AdminAttendanceReasonRequest request) {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/reverse-late-present")
    @Operation(operationId = "adminReverseLatePresent", summary = "Reverse a Mark Present — Late (FR-ATT-12)",
            description = "Before endAt it re-opens no-show eligibility (arch 07 §7.4). Implemented by E7-70.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED)
    @ApiError(status = 422, codes = ApiErrorCode.IDEMPOTENCY_KEY_REUSED)
    public DataResponse<AdminAttendance> reverseLatePresent(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @PathVariable UUID bookingId,
            @Valid @RequestBody AdminAttendanceReasonRequest request) {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/reverse-no-show")
    @Operation(operationId = "adminReverseNoShow", summary = "Reverse a no-show: NO_SHOW_REVERSED (FR-ATT-12)",
            description = "Before the original endAt the booking becomes CANCELLED with ADMIN_NO_SHOW_REVERSAL; after "
                    + "it, COMPLETED with ATTENDANCE_UNCONFIRMED (arch 07 §7.4). To record that the customer did "
                    + "attend, use correct with the chosen outcome (FR-ATT-13). Implemented by E7-70.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED)
    @ApiError(status = 422, codes = ApiErrorCode.IDEMPOTENCY_KEY_REUSED)
    public DataResponse<AdminAttendance> reverseNoShow(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @PathVariable UUID bookingId,
            @Valid @RequestBody AdminAttendanceReasonRequest request) {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/correct")
    @Operation(operationId = "adminCorrectAttendance",
            summary = "Correct the attendance outcome to the one chosen: ATTENDANCE_CORRECTED (FR-ATT-13, FR-TRS-07)",
            description = "correctedOutcome is ATTENDED, ATTENDED_INTERRUPTED, ATTENDANCE_UNCONFIRMED or ABSENT_NO_SHOW; "
                    + "NOT_APPLICABLE is VALIDATION_FAILED on correctedOutcome. The latest non-superseded correction is "
                    + "authoritative, so chained corrections never double a reward or penalty (FR-TRS-09). A correction "
                    + "to ATTENDED uses the points value of the original completion date (FR-TRS-08). Not a refund "
                    + "trigger (FR-PTS-18). Implemented by E7-70.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED)
    @ApiError(status = 422, codes = ApiErrorCode.IDEMPOTENCY_KEY_REUSED)
    public DataResponse<AdminAttendance> correct(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @PathVariable UUID bookingId,
            @Valid @RequestBody AdminAttendanceCorrectionRequest request) {
        throw NotImplementedYet.stub();
    }

    /** The mandatory reason of an attendance remediation (FR-AUD-06), kept in its audit row. */
    @Schema(name = "AdminAttendanceReasonRequest")
    public record AdminAttendanceReasonRequest(
            @NotBlank @Size(min = 1, max = 500) @Schema(example = "Venue checked the customer in on the wrong booking.")
            String reason) {
    }

    @Schema(name = "AdminAttendanceCorrectionRequest")
    public record AdminAttendanceCorrectionRequest(
            @NotNull AttendanceResult correctedOutcome,
            @NotBlank @Size(min = 1, max = 500) @Schema(example = "CCTV shows the customer played the whole session.")
            String reason) {
    }

    /** The booking's attendance after the action (FR-ATT-07); reversed evidence is null. */
    @Schema(name = "AdminAttendance")
    public record AdminAttendance(
            @NotNull @Schema(example = "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e6f") UUID bookingId,
            @NotNull BookingStatus status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) BookingTerminalReason terminalReason,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) AttendanceResult attendanceResult,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "2026-10-12T18:04:00Z")
            Instant checkedInAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "2026-10-12T18:20:00Z")
            Instant latePresentAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "2026-10-12T18:20:00Z")
            Instant noShowAt) {
    }
}
