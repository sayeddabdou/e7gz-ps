package ps.e7gz.identity.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.ResponseMetaFactory;
import ps.e7gz.identity.internal.CustomerRegistration;
import ps.e7gz.identity.internal.PasswordPolicy;

/**
 * Customer registration and phone verification (arch 09 section 2 and section 3.1; FR-ACC-01…06). Public: no access
 * token (arch 09 section 1.2). Size limits below are technical input bounds, not Product rules.
 */
@RestController
@RequestMapping("/api/v1/customer/auth")
@Tag(name = "customer-auth")
public class CustomerAuthController {

    static final int MAX_TEXT = 200;

    private final CustomerRegistration registration;
    private final ResponseMetaFactory meta;

    public CustomerAuthController(CustomerRegistration registration, ResponseMetaFactory meta) {
        this.registration = registration;
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
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "customerVerifyPhone",
            summary = "Verify the phone with the SMS code; the account becomes Account Verified (FR-ACC-02, FR-ACC-04)")
    @ApiError(status = 403, codes = ApiErrorCode.ACCOUNT_DELETED)
    @ApiError(status = 422, codes = {ApiErrorCode.OTP_INVALID, ApiErrorCode.OTP_EXPIRED})
    @ApiError(status = 429, codes = ApiErrorCode.OTP_ATTEMPTS_EXCEEDED)
    public void verifyPhone(@Valid @RequestBody CustomerVerifyPhoneRequest request) {
        registration.verifyPhone(request.otpChallengeId(), request.code());
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
}
