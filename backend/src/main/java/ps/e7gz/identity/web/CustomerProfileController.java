package ps.e7gz.identity.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.NotImplementedYet;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.identity.web.CustomerAuthController.CustomerOtpChallengeView;

/**
 * The customer's own profile (arch 09 §3.1, FRS §4.1). Stubs of the Sprint 3 contract (E7-54) until E7-57; email
 * change and verification wait for the PO's email-verification decision (deferred 30 Sep 2026; stubs kept, PO decision
 * 11 Oct 2026). None of these writes is in ADR-015's scope (no commitment, money or points), so the Idempotency-Key is
 * optional. A phone or email change becomes authoritative only after the new value is verified (FR-ACC-07,
 * VALIDATION RULES). Size limits are technical input bounds, as in registration.
 */
@RestController
@RequestMapping("/api/v1/customer/profile")
@Tag(name = "customer-profile")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class CustomerProfileController {

    @GetMapping
    @Operation(operationId = "customerGetProfile", summary = "My profile (FR-ACC-07)",
            description = "Also for a banned customer (FR-ACC-17). Implemented by E7-57.")
    public DataResponse<CustomerProfile> get() {
        throw NotImplementedYet.stub();
    }

    @PatchMapping
    @Operation(operationId = "customerUpdateProfile", summary = "Change my full name, freely (FR-ACC-07)",
            description = "Username, phone and email have their own routes. Implemented by E7-57.")
    public DataResponse<CustomerProfile> update(@Valid @RequestBody CustomerProfileUpdateRequest request) {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/username")
    @Operation(operationId = "customerChangeUsername",
            summary = "Change my username; then not again for 30 days (FR-ACC-06, FR-ACC-07)",
            description = "Unique on the trimmed, case-insensitive form (FR-ACC-06): USERNAME_TAKEN. Inside the 30-day "
                    + "cooldown it is USERNAME_COOLDOWN_ACTIVE (enforced by the database, Spec v5 §7.5); the profile's "
                    + "usernameChangeAvailableAt says when. Reviews show the new username at once (FR-ACC-08). "
                    + "Implemented by E7-57.")
    @ApiError(status = 422, codes = {ApiErrorCode.USERNAME_TAKEN, ApiErrorCode.USERNAME_COOLDOWN_ACTIVE})
    public DataResponse<CustomerProfile> changeUsername(@Valid @RequestBody CustomerUsernameChangeRequest request) {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/phone")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "customerStartPhoneChange",
            summary = "Send a code to the new phone; the phone changes only once it is verified (FR-ACC-07)",
            description = "A phone held by another verified account is PHONE_TAKEN (FR-ACC-01). The OTP limits of "
                    + "FR-ACC-04 apply. Confirm with POST /customer/profile/phone/verify (PO decision 11 Oct 2026, "
                    + "E7-54). Implemented by E7-57.")
    @ApiError(status = 422, codes = ApiErrorCode.PHONE_TAKEN)
    @ApiError(status = 429, codes = {ApiErrorCode.OTP_RESEND_TOO_SOON, ApiErrorCode.OTP_HOURLY_LIMIT_REACHED})
    public DataResponse<CustomerOtpChallengeView> startPhoneChange(@Valid @RequestBody CustomerPhoneChangeRequest request) {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/phone/verify")
    @Operation(operationId = "customerVerifyPhoneChange",
            summary = "Confirm the new phone with its code; it becomes the account's phone (FR-ACC-07)",
            description = "A route added by the PO decision of 11 Oct 2026 (E7-54) for the second step of the phone "
                    + "change. A challenge that is not this customer's phone change is NOT_FOUND. A phone taken by "
                    + "another account meanwhile is PHONE_TAKEN. Implemented by E7-57.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 422, codes = {ApiErrorCode.OTP_INVALID, ApiErrorCode.OTP_EXPIRED, ApiErrorCode.PHONE_TAKEN})
    @ApiError(status = 429, codes = ApiErrorCode.OTP_ATTEMPTS_EXCEEDED)
    public DataResponse<CustomerProfile> verifyPhoneChange(@Valid @RequestBody CustomerOtpCodeRequest request) {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/email")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "customerStartEmailVerification",
            summary = "Send a code to an email address: a new one to change it, or the current one to verify it "
                    + "(FR-ACC-05, FR-ACC-07)",
            description = "A new email becomes the account's email only once verified (FR-ACC-07); the email is not "
                    + "unique (FR-ACC-01). Email verification is an independent track and never activates the account "
                    + "(FR-ACC-02). Not built yet: email verification was deferred by the PO (30 Sep 2026); E7-57 "
                    + "implements it once the PO decides it (PO decision 11 Oct 2026, E7-54).")
    @ApiError(status = 429, codes = {ApiErrorCode.OTP_RESEND_TOO_SOON, ApiErrorCode.OTP_HOURLY_LIMIT_REACHED})
    public DataResponse<CustomerOtpChallengeView> startEmailVerification(
            @Valid @RequestBody CustomerEmailRequest request) {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/email/verify")
    @Operation(operationId = "customerVerifyEmail",
            summary = "Confirm the email with its code; emailVerified becomes true (FR-ACC-05, FR-ACC-07)",
            description = "A challenge that is not this customer's is NOT_FOUND. Not built yet, like POST "
                    + "/customer/profile/email.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 422, codes = {ApiErrorCode.OTP_INVALID, ApiErrorCode.OTP_EXPIRED})
    @ApiError(status = 429, codes = ApiErrorCode.OTP_ATTEMPTS_EXCEEDED)
    public DataResponse<CustomerProfile> verifyEmail(@Valid @RequestBody CustomerOtpCodeRequest request) {
        throw NotImplementedYet.stub();
    }

    /** FR-ACC-01, FR-ACC-07: what the customer sees and edits; Verified Player and standing are Sprint 4's. */
    @Schema(name = "CustomerProfile")
    public record CustomerProfile(
            @NotNull @Schema(example = "3f6c1d2e-8a4b-4c5d-9e6f-7a8b9c0d1e2f") UUID userId,
            @NotNull @Schema(example = "Omar Hassan") String fullName,
            @NotNull @Schema(example = "omar_h") String username,
            @NotNull @Schema(description = "E.164.", example = "+201012345678") String phone,
            @NotNull @Schema(description = "The account is Account Verified only through the phone (FR-ACC-02).")
            Boolean phoneVerified,
            @NotNull @Schema(example = "omar@example.com") String email,
            @NotNull @Schema(description = "Independent of activation (FR-ACC-05).") Boolean emailVerified,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "While the 30-day cooldown runs: when the username may change again, an instant; "
                            + "null when it may change now (FR-ACC-07).", example = "2026-11-10T12:00:00Z")
            Instant usernameChangeAvailableAt,
            @NotNull @Schema(example = "2026-10-01T09:30:00Z") Instant joinedAt) {
    }

    @Schema(name = "CustomerProfileUpdateRequest")
    public record CustomerProfileUpdateRequest(
            @NotBlank @Size(max = CustomerAuthController.MAX_TEXT) @Schema(example = "Omar Hassan") String fullName) {
    }

    @Schema(name = "CustomerUsernameChangeRequest")
    public record CustomerUsernameChangeRequest(
            @NotBlank @Size(max = CustomerAuthController.MAX_TEXT) @Schema(example = "omar_h") String username) {
    }

    @Schema(name = "CustomerPhoneChangeRequest")
    public record CustomerPhoneChangeRequest(
            @NotBlank @Size(max = 32) @Schema(description = "Egyptian mobile number; stored as E.164.",
                    example = "01012345678") String phone) {
    }

    @Schema(name = "CustomerEmailRequest")
    public record CustomerEmailRequest(
            @NotBlank @Email @Size(max = CustomerAuthController.MAX_TEXT) @Schema(example = "omar@example.com")
            String email) {
    }

    /** The second step of a phone or email change: the challenge and its 6-digit code. */
    @Schema(name = "CustomerOtpCodeRequest")
    public record CustomerOtpCodeRequest(
            @NotNull @Schema(example = "9a1b2c3d-4e5f-4a6b-8c7d-0e1f2a3b4c5d") UUID otpChallengeId,
            @NotBlank @Pattern(regexp = "[0-9]{6}") @Schema(example = "482913") String code) {
    }
}
