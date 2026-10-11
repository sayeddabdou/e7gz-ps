package ps.e7gz.identity.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.NotImplementedYet;
import ps.e7gz.common.web.ResponseMetaFactory;
import ps.e7gz.identity.internal.AuthSessions;
import ps.e7gz.identity.internal.CustomerRegistration;
import ps.e7gz.identity.internal.PasswordPolicy;

/**
 * Customer registration, phone verification, sessions and password reset (arch 09 section 2 and section 3.1;
 * FR-ACC-01…06, FR-ACC-09; arch 10 section 2). Public: no access token (arch 09 section 1.2). Password reset is a stub of
 * the Sprint 3 contract (E7-54) until E7-57. Size limits below are technical input bounds, not Product rules.
 */
@RestController
@RequestMapping("/api/v1/customer/auth")
@Tag(name = "customer-auth")
public class CustomerAuthController {

    static final int MAX_TEXT = 200;

    private final CustomerRegistration registration;
    private final AuthSessions sessions;
    private final ResponseMetaFactory meta;

    public CustomerAuthController(CustomerRegistration registration, AuthSessions sessions, ResponseMetaFactory meta) {
        this.registration = registration;
        this.sessions = sessions;
        this.meta = meta;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "customerRegister",
            summary = "Register; the account stays unverified until the phone OTP is verified (FR-ACC-01, FR-ACC-02)")
    @ApiError(status = 422, codes = {ApiErrorCode.USERNAME_TAKEN, ApiErrorCode.PHONE_TAKEN})
    @ApiError(status = 429, codes = {ApiErrorCode.OTP_RESEND_TOO_SOON, ApiErrorCode.OTP_HOURLY_LIMIT_REACHED})
    public DataResponse<CustomerRegistrationView> register(@Valid @RequestBody CustomerRegisterRequest request) {
        CustomerRegistration.Registered registered = registration.register(new CustomerRegistration.NewCustomer(
                request.fullName(), request.username(), request.phone(), request.email(), request.password()));
        return meta.data(new CustomerRegistrationView(registered.userId(), registered.otpChallengeId()));
    }

    @PostMapping("/otp/send")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "customerSendPhoneOtp",
            summary = "Send a new phone verification code; the previous code stops working (FR-ACC-04)")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 429, codes = {ApiErrorCode.OTP_RESEND_TOO_SOON, ApiErrorCode.OTP_HOURLY_LIMIT_REACHED})
    public DataResponse<CustomerOtpChallengeView> sendOtp(@Valid @RequestBody CustomerSendOtpRequest request) {
        return meta.data(new CustomerOtpChallengeView(registration.sendPhoneVerificationOtp(request.phone())));
    }

    @PostMapping("/verify-phone")
    @Operation(operationId = "customerVerifyPhone",
            summary = "Verify the phone with the SMS code; the account becomes Account Verified and a session starts "
                    + "(FR-ACC-02, FR-ACC-04, arch 09 section 2)")
    @ApiError(status = 403, codes = ApiErrorCode.ACCOUNT_DELETED)
    @ApiError(status = 422, codes = {ApiErrorCode.OTP_INVALID, ApiErrorCode.OTP_EXPIRED})
    @ApiError(status = 429, codes = ApiErrorCode.OTP_ATTEMPTS_EXCEEDED)
    public DataResponse<CustomerAuthTokens> verifyPhone(@Valid @RequestBody CustomerVerifyPhoneRequest request) {
        UUID userId = registration.verifyPhone(request.otpChallengeId(), request.code());
        AuthSessions.TokenPair tokens = sessions.start(AuthSubjectType.CUSTOMER, userId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.ACCOUNT_DELETED));          // anonymised meanwhile
        return meta.data(CustomerAuthTokens.of(tokens));
    }

    @PostMapping("/login")
    @Operation(operationId = "customerLogin",
            summary = "Log in with username or phone and password; an unverified account can log in (arch 10 "
                    + "section 2.1, FR-ACC-03)")
    @ApiError(status = 401, codes = ApiErrorCode.UNAUTHENTICATED)
    public DataResponse<CustomerAuthTokens> login(@Valid @RequestBody CustomerLoginRequest request,
                                                  HttpServletRequest http) {
        return meta.data(CustomerAuthTokens.of(sessions.login(
                AuthSubjectType.CUSTOMER, request.usernameOrPhone(), request.password(), http.getRemoteAddr())));
    }

    @PostMapping("/refresh")
    @Operation(operationId = "customerRefresh",
            summary = "Exchange the refresh token for a new pair; the old refresh token stops working (arch 10 "
                    + "section 2.3)")
    @ApiError(status = 401, codes = ApiErrorCode.UNAUTHENTICATED)
    public DataResponse<CustomerAuthTokens> refresh(@Valid @RequestBody CustomerRefreshTokenRequest request) {
        return meta.data(CustomerAuthTokens.of(sessions.refresh(AuthSubjectType.CUSTOMER, request.refreshToken())));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "customerLogout", summary = "End the session of this refresh token (arch 10 section 2.3)")
    public void logout(@Valid @RequestBody CustomerRefreshTokenRequest request) {
        sessions.logout(AuthSubjectType.CUSTOMER, request.refreshToken());
    }

    @PostMapping("/password-reset/request")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "customerRequestPasswordReset",
            summary = "Send a password reset code to the account's verified phone (FR-ACC-09)",
            description = "By phone OTP only: reset by email waits for email verification (PO decision 11 Oct 2026, "
                    + "E7-54). A phone with no account, or whose account never verified it, is NOT_FOUND, like "
                    + "otp/send. The OTP limits of FR-ACC-04 apply; a new code supersedes the previous one. "
                    + "Implemented by E7-57.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 429, codes = {ApiErrorCode.OTP_RESEND_TOO_SOON, ApiErrorCode.OTP_HOURLY_LIMIT_REACHED})
    public DataResponse<CustomerOtpChallengeView> requestPasswordReset(
            @Valid @RequestBody CustomerPasswordResetRequest request) {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/password-reset/complete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "customerCompletePasswordReset",
            summary = "Set a new password with the reset code; every session ends (FR-ACC-09)",
            description = "No old password (FR-ACC-09). The new password follows VALIDATION RULES §2.1, otherwise 400 "
                    + "VALIDATION_FAILED on field password. Every refresh token of the account is revoked, so the "
                    + "customer logs in again (PO decision 11 Oct 2026, E7-54). Implemented by E7-57.")
    @ApiError(status = 403, codes = ApiErrorCode.ACCOUNT_DELETED)
    @ApiError(status = 422, codes = {ApiErrorCode.OTP_INVALID, ApiErrorCode.OTP_EXPIRED})
    @ApiError(status = 429, codes = ApiErrorCode.OTP_ATTEMPTS_EXCEEDED)
    public void completePasswordReset(@Valid @RequestBody CustomerPasswordResetCompleteRequest request) {
        throw NotImplementedYet.stub();
    }

    /** Arch 09 section 2: {@code {fullName, username, phone, email, password}}, all required (FR-ACC-01). */
    @Schema(name = "CustomerRegisterRequest")
    public record CustomerRegisterRequest(
            @NotBlank @Size(max = MAX_TEXT) @Schema(example = "Omar Hassan") String fullName,
            @NotBlank @Size(max = MAX_TEXT) @Schema(example = "omar_h") String username,
            @NotBlank @Size(max = 32) @Schema(description = "Egyptian mobile number; stored as E.164.",
                    example = "01012345678") String phone,
            @NotBlank @Email @Size(max = MAX_TEXT) @Schema(example = "omar@example.com") String email,
            @NotBlank @Size(min = PasswordPolicy.MIN_LENGTH, max = MAX_TEXT)
            @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY,
                    description = "At least 8 characters, with at least one letter and one digit "
                            + "(VALIDATION RULES §2.1). Otherwise 400 VALIDATION_FAILED on field password.")
            String password) {
    }

    @Schema(name = "CustomerRegistration")
    public record CustomerRegistrationView(
            @NotNull @Schema(example = "3f6c1d2e-8a4b-4c5d-9e6f-7a8b9c0d1e2f") UUID userId,
            @NotNull @Schema(example = "9a1b2c3d-4e5f-4a6b-8c7d-0e1f2a3b4c5d") UUID otpChallengeId) {
    }

    @Schema(name = "CustomerSendOtpRequest")
    public record CustomerSendOtpRequest(
            @NotBlank @Size(max = 32) @Schema(example = "01012345678") String phone) {
    }

    @Schema(name = "CustomerOtpChallenge")
    public record CustomerOtpChallengeView(
            @NotNull @Schema(example = "9a1b2c3d-4e5f-4a6b-8c7d-0e1f2a3b4c5d") UUID otpChallengeId) {
    }

    @Schema(name = "CustomerVerifyPhoneRequest")
    public record CustomerVerifyPhoneRequest(
            @NotNull @Schema(example = "9a1b2c3d-4e5f-4a6b-8c7d-0e1f2a3b4c5d") UUID otpChallengeId,
            @NotBlank @Pattern(regexp = "[0-9]{6}") @Schema(example = "482913") String code) {
    }

    @Schema(name = "CustomerPasswordResetRequest")
    public record CustomerPasswordResetRequest(
            @NotBlank @Size(max = 32) @Schema(example = "01012345678") String phone) {
    }

    /** FR-ACC-09: the reset code and the new password; no old password. */
    @Schema(name = "CustomerPasswordResetCompleteRequest")
    public record CustomerPasswordResetCompleteRequest(
            @NotNull @Schema(example = "9a1b2c3d-4e5f-4a6b-8c7d-0e1f2a3b4c5d") UUID otpChallengeId,
            @NotBlank @Pattern(regexp = "[0-9]{6}") @Schema(example = "482913") String code,
            @NotBlank @Size(min = PasswordPolicy.MIN_LENGTH, max = MAX_TEXT)
            @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY,
                    description = "At least 8 characters, with at least one letter and one digit "
                            + "(VALIDATION RULES §2.1). Otherwise 400 VALIDATION_FAILED on field password.")
            String password) {

        @Override
        public String toString() {
            return "CustomerPasswordResetCompleteRequest[<redacted>]";
        }
    }

    @Schema(name = "CustomerLoginRequest")
    public record CustomerLoginRequest(
            @NotBlank @Size(max = MAX_TEXT) @Schema(description = "Username, or phone in any accepted form.",
                    example = "omar_h") String usernameOrPhone,
            @NotBlank @Size(max = MAX_TEXT) @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY)
            String password) {

        @Override
        public String toString() {
            return "CustomerLoginRequest[<redacted>]";
        }
    }

    /** Refresh and logout. */
    @Schema(name = "CustomerRefreshTokenRequest")
    public record CustomerRefreshTokenRequest(
            @NotBlank @Size(max = MAX_TEXT) @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) String refreshToken) {

        @Override
        public String toString() {
            return "CustomerRefreshTokenRequest[<redacted>]";
        }
    }

    /** Arch 09 section 2: {@code {accessToken, refreshToken}}. */
    @Schema(name = "CustomerAuthTokens")
    public record CustomerAuthTokens(
            @NotNull @Schema(description = "Access JWT, 15 minutes; send as Authorization: Bearer.") String accessToken,
            @NotNull @Schema(description = "Opaque refresh token, 30 days; rotated on every refresh.")
            String refreshToken) {

        static CustomerAuthTokens of(AuthSessions.TokenPair tokens) {
            return new CustomerAuthTokens(tokens.accessToken(), tokens.refreshToken());
        }

        @Override
        public String toString() {
            return "CustomerAuthTokens[<redacted>]";
        }
    }
}
